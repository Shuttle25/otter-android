package app.ottermsg.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

sealed interface ConnectionState {
    data object Offline : ConnectionState
    data object Connecting : ConnectionState
    data class Online(val api: NodeApi, val url: String) : ConnectionState
    data class Waiting(val retryAtMs: Long, val reason: String) : ConnectionState
}

/**
 * Keeps one node connection alive while started. [onConnected] runs after hello (e.g. login + initial sync)
 * with a session scope as receiver that is cancelled when this connection ends; if it throws, the
 * connection is dropped and retried.
 */
class ConnectionManager(
    private val transport: Transport,
    private val urls: suspend () -> List<String>,
    private val scope: CoroutineScope,
    private val onConnected: suspend CoroutineScope.(NodeApi) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    val state: StateFlow<ConnectionState> = _state
    private var job: Job? = null
    private var rpc: RpcClient? = null

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
    fun retryNow() { if (_state.value is ConnectionState.Waiting) { stop(); start() } }

    private suspend fun loop() {
        var backoffMs = 1_000L
        while (scope.isActive) {
            _state.value = ConnectionState.Connecting
            var lastError = "no endpoints"
            for (url in urls()) {
                val r = try {
                    val conn = transport.connect(url)
                    RpcClient(conn, scope).also { rpc = it }
                } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName; continue }
                val session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
                try {
                    withTimeout(10_000) { r.hello.await() }
                    val api = RemoteNodeApi(r, clock)
                    session.onConnected(api)
                    _state.value = ConnectionState.Online(api, url)
                    backoffMs = 1_000L
                    r.closed.await()
                    lastError = "connection lost"
                } catch (e: Exception) {
                    if (!scope.isActive) return
                    lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    session.cancel()
                    r.close(); rpc = null
                }
                break // after a session ended (or failed after connect), restart from the first endpoint after backoff
            }
            val jitter = 1.0 + (random.nextDouble() - 0.5) * 0.4
            val wait = (backoffMs * jitter).toLong()
            _state.value = ConnectionState.Waiting(clock() + wait, lastError)
            delay(wait)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000)
        }
    }
}
