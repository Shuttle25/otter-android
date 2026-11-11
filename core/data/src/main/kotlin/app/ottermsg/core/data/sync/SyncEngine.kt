package app.ottermsg.core.data.sync

import app.ottermsg.core.crypto.ManifestVerifier
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
import app.ottermsg.core.database.dao.ManifestDao
import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.ConnectionManager
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.network.Endpoints
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.EndpointStats
import app.ottermsg.core.network.NodeSelector
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.RecordBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
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
    selector: NodeSelector, stats: EndpointStats,
    private val accounts: AccountRepository, private val settings: Settings, private val manifests: ManifestDao,
    private val directory: DirectorySync, private val receiver: MailboxReceiver, private val outbox: Outbox,
    private val prekeys: PrekeyRefill,
    @ApplicationScope scope: CoroutineScope, @param:AllowPlaintext private val allowPlaintext: Boolean, clock: Clock,
) : OnlineApi {
    private val manager = ConnectionManager(selector, ::candidates, stats, scope, { api -> runSession(api, this) }, clock::now)
    val connection: StateFlow<ConnectionState> = manager.state

    fun start() = manager.start()
    fun stop() = manager.stop()
    fun retryNow() = manager.retryNow()
    override fun current(): NodeApi? = (connection.value as? ConnectionState.Online)?.api

    private suspend fun candidates(): List<Candidate> {
        val m = manifests.latest() ?: return emptyList()
        return Endpoints.candidates(ManifestVerifier.verify(m.raw, settings.networkPub(), 0), allowPlaintext)
    }

    /** One connected session; [session] is cancelled by the ConnectionManager when the connection ends. */
    suspend fun runSession(api: NodeApi, session: CoroutineScope) {
        val me = accounts.require()
        // Subscribe before login: the node pushes queued envelopes right after auth.login.
        session.launch(start = CoroutineStart.UNDISPATCHED) { api.events.collect { e -> onEvent(api, e) } }
        api.login(me.userId, me.accountKey)
        directory.sync(api)
        prekeys.refillIfPending(api)
        session.launch { outbox.run(api) }
    }

    private suspend fun onEvent(api: NodeApi, e: Event) {
        try {
            when (e.type) {
                "envelopes" -> receiver.handle(api, RecordBatch.parseFrom(e.body).recordsList)
                "directory" -> directory.sync(api)
                "prekeys_low" -> prekeys.refill(api)
            }
        } catch (x: CancellationException) {
            throw x
        } catch (x: Exception) {
            // Unacked envelopes are redelivered on the next connection; nothing is lost.
            log.log(Level.WARNING, "event ${e.type} failed", x)
        }
    }

    private companion object { val log: Logger = Logger.getLogger("SyncEngine") }
}
