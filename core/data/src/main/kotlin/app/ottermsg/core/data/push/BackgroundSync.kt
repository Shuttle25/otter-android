package app.ottermsg.core.data.push

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.network.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One background wake (D61): connect, drain the mailbox, flush the outbox, go offline after [IDLE_MS] of quiet. */
@Singleton
class BackgroundSync @Inject constructor(
    private val engine: SyncEngine, private val monitor: PushMonitor, private val clock: Clock,
) {
    /** True when the engine got online and went quiet within [maxMs]; the WAKE lease is released either way. */
    suspend fun drain(fromPush: Boolean, maxMs: Long = MAX_MS, idleMs: Long = IDLE_MS): Boolean {
        if (fromPush) monitor.onWake()
        engine.startWake()
        try {
            return withTimeoutOrNull(maxMs) {
                engine.connection.first { it is ConnectionState.Online }
                while (clock.now() - engine.lastActivityMs < idleMs || engine.outboxHasDue()) delay(POLL_MS)
                true
            } ?: false
        } finally {
            engine.release(SyncEngine.WAKE) // the FOREGROUND lease (if the app became visible) keeps it running
        }
    }

    companion object { const val MAX_MS = 60_000L; const val IDLE_MS = 10_000L; const val POLL_MS = 500L }
}
