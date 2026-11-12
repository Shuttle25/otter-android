package app.ottermsg.core.data.sync

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.InstallGuard
import app.ottermsg.core.data.media.MediaTransfers
import app.ottermsg.core.data.profile.ProfileRepository
import app.ottermsg.core.data.di.AllowPlaintext
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.data.messaging.MailboxReceiver
import app.ottermsg.core.data.messaging.Outbox
import app.ottermsg.core.data.push.PushMonitor
import app.ottermsg.core.data.push.PushRegistrar
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.signal.PrekeyRefill
import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.ConnectionManager
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.EndpointStats
import app.ottermsg.core.network.NodeSelector
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.ManifestResponse
import app.ottermsg.proto.v1.PrekeysLow
import app.ottermsg.proto.v1.RecordBatch
import app.ottermsg.proto.v1.RecordBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps the account connected while started: login → directory sync → pending prekey refill → outbox,
 * while handling node events (envelopes, directory, prekeys_low) for the lifetime of each connection.
 */
@Singleton
class SyncEngine @Inject constructor(
    stats: EndpointStats,
    private val selector: NodeSelector,
    private val accounts: AccountRepository, private val settings: Settings, private val manifests: ManifestUpdater,
    private val directory: DirectorySync, private val receiver: MailboxReceiver, private val outbox: Outbox,
    private val prekeys: PrekeyRefill, private val profile: ProfileRepository, private val media: MediaTransfers,
    @ApplicationScope private val appScope: CoroutineScope, @param:AllowPlaintext private val allowPlaintext: Boolean,
    private val clock: Clock, private val guard: InstallGuard, private val backups: BackupManager,
    private val pushRegistrar: PushRegistrar = PushRegistrar(accounts, settings, clock),
    private val pushMonitor: PushMonitor = PushMonitor(settings, clock),
) : OnlineApi {
    private val manager = ConnectionManager(selector, ::candidates, stats, appScope, { api -> runSession(api, this) }, clock::now)
    val connection: StateFlow<ConnectionState> = manager.state

    private val leases = mutableSetOf<String>()
    /** Last login or `envelopes` event (BackgroundSync's idle clock). */
    @Volatile var lastActivityMs: Long = 0L; private set
    @Volatile private var sessionStartMs = 0L
    @Volatile private var sessionId = 0L

    /** Runs while at least one lease is held (D61): [FOREGROUND] (app visible) or [WAKE] (push / battery saver). */
    @Synchronized fun acquire(tag: String) { val idle = leases.isEmpty(); leases += tag; if (idle) start() }
    @Synchronized fun release(tag: String) {
        if (!leases.remove(tag) || leases.isNotEmpty()) return
        stop()
        appScope.launch { pushMonitor.onOffline() }
    }
    @Synchronized fun holds(tag: String) = tag in leases

    /** A push or the battery saver: give the last-good node a 2 s head start, then race (docs/05). */
    fun startWake() { manager.nextHeadStartMs = WAKE_HEAD_START_MS; acquire(WAKE) }

    suspend fun outboxHasDue(): Boolean = outbox.hasDue()

    /** Set once another phone restored this account (D52): the engine never connects again. */
    @Volatile private var superseded = false

    fun start() { if (!superseded) manager.start() }
    fun stop() = manager.stop()
    fun retryNow() = manager.retryNow()
    /** Hook for tests; drops the connection and races all candidates again. */
    internal var reconnect: () -> Unit = manager::reconnect
    override fun current(): NodeApi? = (connection.value as? ConnectionState.Online)?.api

    private suspend fun candidates(): List<Candidate> = manifests.candidates(allowPlaintext)

    /** Default network switched (Wi-Fi ↔ mobile): a live connection may be stale, a waiting one may work now. */
    fun onNetworkChanged() = manager.networkChanged()

    /** One connected session; [session] is cancelled by the ConnectionManager when the connection ends. */
    suspend fun runSession(api: NodeApi, session: CoroutineScope) {
        val me = accounts.require()
        if (settings.superseded()) { superseded = true; stop(); return }
        // Subscribe before login: the node pushes queued envelopes right after auth.login.
        session.launch(start = CoroutineStart.UNDISPATCHED) { api.events.collect { e -> onEvent(api, e) } }
        val login = api.login(me.userId, me.accountKey)
        if (!guard.afterLogin(api, login)) {
            superseded = true
            stop() // this install was replaced by a restore on another phone; the UI shows it (settings.superseded)
            return
        }
        sessionStartMs = clock.now(); sessionId++; lastActivityMs = sessionStartMs
        session.launch {
            try { pushRegistrar.registerIfNeeded(api) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.log(Level.WARNING, "push token registration failed", e) } // retried next login
        }
        if (manifests.checkAfterLogin(api) == ManifestUpdater.Result.STORED_NODE_REMOVED) {
            reconnect() // this node is no longer part of the network
            return
        }
        directory.sync(api)
        prekeys.refillIfPending(api)
        session.launch { profile.run(api) }
        session.launch { media.run(api) }
        session.launch { outbox.run(api) }
        session.launch { backups.runIfDue(api) }
        session.launch { reprobeLoop(api) }
    }

    /**
     * Every 30 min while online: probe the other candidates and move
     * when one is clearly faster (RTT < 70 % of ours) three times in a row.
     */
    private suspend fun reprobeLoop(api: NodeApi) {
        val policy = ReprobePolicy()
        val self = api.hello().nodeId.toByteArray()
        while (true) {
            delay(REPROBE_EVERY_MS)
            val t0 = clock.now()
            try { api.ping() } catch (e: CancellationException) { throw e } catch (e: Exception) { continue }
            val currentRtt = clock.now() - t0
            val others = candidates().filterNot { it.nodeId.contentEquals(self) }
            if (others.isEmpty()) continue
            val best = coroutineScope { selector.probe(others, this) }
            if (policy.observe(currentRtt, best)) reconnect()
        }
    }

    private suspend fun onEvent(api: NodeApi, e: Event) {
        try {
            when (e.type) {
                "envelopes" -> {
                    lastActivityMs = clock.now()
                    val records = RecordBatch.parseFrom(e.body).recordsList
                    receiver.handle(api, records)
                    pushMonitor.onDelivered(records.map { RecordBody.parseFrom(it.body).createdMs }, sessionStartMs, sessionId)
                    lastActivityMs = clock.now()
                }
                "directory" -> directory.sync(api)
                "prekeys_low" -> prekeys.refill(api, PrekeysLow.parseFrom(e.body).ownerNode.toByteArray())
                "manifest" -> {
                    val raw = ManifestResponse.parseFrom(e.body).manifest.toByteArray()
                    if (manifests.offer(raw, api.hello().nodeId.toByteArray()) == ManifestUpdater.Result.STORED_NODE_REMOVED) reconnect()
                }
            }
        } catch (x: CancellationException) {
            throw x
        } catch (x: Exception) {
            // Unacked envelopes are redelivered on the next connection; nothing is lost.
            log.log(Level.WARNING, "event ${e.type} failed", x)
        }
    }

    companion object {
        const val REPROBE_EVERY_MS = 30 * 60_000L
        const val FOREGROUND = "foreground"
        const val WAKE = "wake"
        const val WAKE_HEAD_START_MS = 2_000L
        private val log: Logger = Logger.getLogger("SyncEngine")
    }
}

/** Switch nodes only when another one is clearly faster in [needed] consecutive probes. */
class ReprobePolicy(private val ratio: Double = 0.7, private val needed: Int = 3) {
    private var streak = 0
    fun observe(currentRttMs: Long, bestOtherRttMs: Long?): Boolean {
        streak = if (bestOtherRttMs != null && bestOtherRttMs < currentRttMs * ratio) streak + 1 else 0
        return (streak >= needed).also { if (it) streak = 0 }
    }
}
