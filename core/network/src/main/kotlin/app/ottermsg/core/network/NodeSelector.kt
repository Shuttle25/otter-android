package app.ottermsg.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /**
     * Returns the first candidate that completes transport + hello, or null if all fail. [scope] owns the winner's RpcClient.
     * With [headStartMs] > 0 and a recently good first candidate, the others start only after [headStartMs] (wake-ups, D61).
     */
    suspend fun race(cands: List<Candidate>, scope: CoroutineScope, headStartMs: Long = 0): Winner? = coroutineScope {
        val ordered = order(cands)
        if (ordered.isEmpty()) return@coroutineScope null
        val lead = headStartMs > 0 && ordered.size > 1 && recentlyGood(ordered[0])
        val results = Channel<Pair<Candidate, Winner?>>(Channel.UNLIMITED)
        val gate = Semaphore(maxParallel)
        val jobs = ordered.mapIndexed { i, c ->
            launch {
                delay(if (i == 0) 0 else if (lead) headStartMs + (i - 1) * staggerMs else i * staggerMs)
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

    /** Connects to each candidate (staggered), records its RTT or failure, closes it. Returns the best RTT, or null. */
    suspend fun probe(cands: List<Candidate>, scope: CoroutineScope): Long? = coroutineScope {
        val gate = Semaphore(maxParallel)
        cands.mapIndexed { i, c ->
            async {
                delay(i * staggerMs)
                gate.withPermit {
                    val t0 = clock()
                    try {
                        val rpc = RpcClient(transport.connect(c), scope)
                        try {
                            withTimeout(helloTimeoutMs) { rpc.hello.await() }
                            (clock() - t0).also { stats.success(c.key, it, clock()) }
                        } finally { rpc.close() }
                    } catch (e: TimeoutCancellationException) {
                        stats.failure(c.key, clock()); null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        stats.failure(c.key, clock()); null
                    }
                }
            }
        }.awaitAll().filterNotNull().minOrNull()
    }

    private suspend fun recentlyGood(c: Candidate): Boolean {
        val s = stats.get(c.key) ?: return false
        val now = clock()
        return s.lastSuccessMs > 0 && now - s.lastSuccessMs < RECENT_SUCCESS_MS && s.lastSuccessMs >= s.lastFailureMs
    }

    private companion object {
        const val RECENT_FAILURE_MS = 10 * 60_000L
        const val RECENT_SUCCESS_MS = 7 * 86_400_000L
    }
}
