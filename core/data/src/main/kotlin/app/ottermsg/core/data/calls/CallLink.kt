package app.ottermsg.core.data.calls

import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.network.NodeApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Provider

/** CallManager's view of the node connection (a seam for tests; SyncEngine in the app). */
interface CallLink {
    /** The live connection, waiting up to [timeoutMs] for one; null when still offline. */
    suspend fun api(timeoutMs: Long): NodeApi?
    fun acquire(tag: String)
    fun release(tag: String)
}

/** Provider: SyncEngine → MailboxReceiver → CallInbox (CallManager) → CallLink would otherwise be a cycle. */
class SyncCallLink @Inject constructor(private val engine: Provider<SyncEngine>) : CallLink {
    override suspend fun api(timeoutMs: Long): NodeApi? {
        val e = engine.get()
        e.current()?.let { return it }
        return withTimeoutOrNull(timeoutMs) { (e.connection.first { it is ConnectionState.Online } as ConnectionState.Online).api }
    }
    override fun acquire(tag: String) = engine.get().acquire(tag)
    override fun release(tag: String) = engine.get().release(tag)
}
