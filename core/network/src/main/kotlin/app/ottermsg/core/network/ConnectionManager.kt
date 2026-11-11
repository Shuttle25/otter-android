package app.ottermsg.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

sealed interface ConnectionState {
    data object Offline : ConnectionState
    data object Connecting : ConnectionState
    data class Online(val api: NodeApi, val url: String, val nodeLabel: String) : ConnectionState
    data class Waiting(val retryAtMs: Long, val reason: String) : ConnectionState
}

/**
 * Keeps one node connection alive while started. Candidates are raced ([NodeSelector]); [onConnected] runs after
 * hello with a session scope (cancelled when the connection ends). If it throws (e.g. RETRY_LATER: this node lacks
 * our account yet), the remaining candidates are raced immediately; when all fail → backoff 1 s → 60 s ±20 %.
 */
class ConnectionManager(
    private val selector: NodeSelector,
    private val candidates: suspend () -> List<Candidate>,
    private val stats: EndpointStats,
    private val scope: CoroutineScope,
    private val onConnected: suspend CoroutineScope.(NodeApi) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    val state: StateFlow<ConnectionState> = _state
    private var job: Job? = null
    @Volatile private var rpc: RpcClient? = null
    @Volatile private var raceNow = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    @Synchronized fun stop() {
        job?.cancel(); job = null
        rpc?.close(); rpc = null
        _state.value = ConnectionState.Offline
    }

    /** Skips the current backoff wait (e.g. network became available). */
    fun retryNow() { wake.trySend(Unit) }

    /** Drops the current connection and races all candidates again without backoff. */
    fun reconnect() {
        raceNow = true
        rpc?.close()
        wake.trySend(Unit)
    }

    private suspend fun loop() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            raceNow = false
            _state.value = ConnectionState.Connecting
            var remaining = candidates()
            var lastError = if (remaining.isEmpty()) "no endpoints" else "unreachable"
            while (remaining.isNotEmpty()) {
                val w = selector.race(remaining, scope) ?: break
                val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
                rpc = w.rpc
                try {
                    val api = RemoteNodeApi(w.rpc, clock)
                    session.onConnected(api)
                    _state.value = ConnectionState.Online(api, w.candidate.url, w.candidate.label)
                    backoffMs = 1_000L
                    w.rpc.closed.await()
                    lastError = "connection lost"
                    remaining = emptyList() // session ended: race all again (after a short wait unless reconnect())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    stats.failure(w.candidate.key, clock())
                    remaining = remaining.filter { it.key != w.candidate.key } // failed after connecting: try the others now
                } finally {
                    session.cancel()
                    w.rpc.close(); rpc = null
                }
            }
            if (raceNow) { raceNow = false; wake.tryReceive(); continue }
            val wait = (backoffMs * (1.0 + (random.nextDouble() - 0.5) * 0.4)).toLong()
            _state.value = ConnectionState.Waiting(clock() + wait, lastError)
            wake.tryReceive() // drop stale wake-ups
            withTimeoutOrNull(wait) { wake.receive() }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000)
        }
    }
}
