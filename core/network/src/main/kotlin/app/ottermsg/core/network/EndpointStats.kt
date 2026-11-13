package app.ottermsg.core.network

data class EndpointStat(
    val lastSuccessMs: Long = 0, val lastFailureMs: Long = 0, val rttMs: Long = 0, val failures: Int = 0,
    val throttleCount: Int = 0, val throttleWindowStartMs: Long = 0, val demotedUntilMs: Long = 0,
)

/** What worked recently, per candidate key (persisted by core:data). */
interface EndpointStats {
    suspend fun get(key: String): EndpointStat?
    suspend fun success(key: String, rttMs: Long, nowMs: Long)
    suspend fun failure(key: String, nowMs: Long)
    /** A "reset after N KB" (D73): 3 within 24 h demote the endpoint for 24 h. */
    suspend fun throttled(key: String, nowMs: Long)
}

/** Shared throttle rule for both implementations. */
fun EndpointStat.withThrottle(nowMs: Long): EndpointStat {
    val fresh = throttleCount == 0 || nowMs - throttleWindowStartMs >= DAY_MS
    val count = if (fresh) 1 else throttleCount + 1
    val start = if (fresh) nowMs else throttleWindowStartMs
    return copy(lastFailureMs = nowMs, failures = failures + 1, throttleCount = count, throttleWindowStartMs = start,
        demotedUntilMs = if (count >= 3) nowMs + DAY_MS else demotedUntilMs)
}

const val DAY_MS = 86_400_000L

class InMemoryEndpointStats : EndpointStats {
    private val map = HashMap<String, EndpointStat>()
    override suspend fun get(key: String) = synchronized(map) { map[key] }
    override suspend fun success(key: String, rttMs: Long, nowMs: Long) = synchronized(map) {
        map[key] = (map[key] ?: EndpointStat()).copy(lastSuccessMs = nowMs, rttMs = rttMs, failures = 0)
    }
    override suspend fun failure(key: String, nowMs: Long) = synchronized(map) {
        val s = map[key] ?: EndpointStat()
        map[key] = s.copy(lastFailureMs = nowMs, failures = s.failures + 1)
    }
    override suspend fun throttled(key: String, nowMs: Long) = synchronized(map) {
        map[key] = (map[key] ?: EndpointStat()).withThrottle(nowMs)
    }
}
