package app.ottermsg.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Races endpoint candidates (docs/05): best known first, staggered starts, first transport + hello wins. */
class NodeSelector(
    private val transport: Transport, private val stats: EndpointStats, private val clock: () -> Long,
    private val staggerMs: Long = 250, private val maxParallel: Int = 4, private val helloTimeoutMs: Long = 10_000,
) {
    class Winner(val candidate: Candidate, val rpc: RpcClient, val rttMs: Long)

    /** Recently successful (by RTT), then untried/old (by priority), then recently failed. */
    suspend fun order(cands: List<Candidate>): List<Candidate> {
        val now = clock()
        val withStats = cands.mapIndexed { i, c -> Triple(i, c, stats.get(c.key)) }
        fun group(s: EndpointStat?) = when {
            s != null && now - s.lastFailureMs < RECENT_FAILURE_MS && s.lastFailureMs > s.lastSuccessMs -> 2
            s != null && s.lastSuccessMs > 0 && now - s.lastSuccessMs < RECENT_SUCCESS_MS -> 0
            else -> 1
        }
        return withStats.sortedWith(compareBy<Triple<Int, Candidate, EndpointStat?>>({ group(it.third) },
            { if (group(it.third) == 0) it.third!!.rttMs else 0L }, { it.second.priority }, { it.first })).map { it.second }
    }

    /** Returns the first candidate that completes transport + hello, or null if all fail. [scope] owns the winner's RpcClient. */
    suspend fun race(cands: List<Candidate>, scope: CoroutineScope): Winner? = coroutineScope {
        val ordered = order(cands)
        if (ordered.isEmpty()) return@coroutineScope null
        val results = Channel<Pair<Candidate, Winner?>>(Channel.UNLIMITED)
        val gate = Semaphore(maxParallel)
        val jobs = ordered.mapIndexed { i, c ->
            launch {
                delay(i * staggerMs)
                gate.withPermit {
                    val t0 = clock()
                    val w = try {
                        val rpc = RpcClient(transport.connect(c), scope)
                        try {
                            withTimeout(helloTimeoutMs) { rpc.hello.await() }
                            Winner(c, rpc, clock() - t0)
                        } catch (e: Throwable) { rpc.close(); throw e }
                    } catch (e: TimeoutCancellationException) {
                        null // no hello in time: a failure, not a cancellation of the race
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    results.send(c to w)
                }
            }
        }
        var winner: Winner? = null
        for (n in ordered.indices) {
            val (c, w) = results.receive()
            if (w == null) { stats.failure(c.key, clock()); continue }
            winner = w
            stats.success(c.key, w.rttMs, clock())
            break
        }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
        while (true) { // losers that finished just before cancellation
            val late = results.tryReceive().getOrNull() ?: break
            late.second?.rpc?.close()
        }
        winner
    }

    private companion object {
        const val RECENT_FAILURE_MS = 10 * 60_000L
        const val RECENT_SUCCESS_MS = 7 * 86_400_000L
    }
}
