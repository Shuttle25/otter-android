package app.ottermsg.core.data.sync

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.di.AllowPlaintext
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.data.messaging.MailboxReceiver
import app.ottermsg.core.data.messaging.Outbox
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
    private val prekeys: PrekeyRefill,
    @ApplicationScope scope: CoroutineScope, @param:AllowPlaintext private val allowPlaintext: Boolean, private val clock: Clock,
) : OnlineApi {
    private val manager = ConnectionManager(selector, ::candidates, stats, scope, { api -> runSession(api, this) }, clock::now)
    val connection: StateFlow<ConnectionState> = manager.state

    fun start() = manager.start()
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
        // Subscribe before login: the node pushes queued envelopes right after auth.login.
        session.launch(start = CoroutineStart.UNDISPATCHED) { api.events.collect { e -> onEvent(api, e) } }
        api.login(me.userId, me.accountKey)
        if (manifests.checkAfterLogin(api) == ManifestUpdater.Result.STORED_NODE_REMOVED) {
            reconnect() // this node is no longer part of the network
            return
        }
        directory.sync(api)
        prekeys.refillIfPending(api)
        session.launch { outbox.run(api) }
        session.launch { reprobeLoop(api) }
    }

    /**
     * Every 30 min while online (the engine only runs in the foreground): probe the other candidates and move
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
                "envelopes" -> receiver.handle(api, RecordBatch.parseFrom(e.body).recordsList)
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
