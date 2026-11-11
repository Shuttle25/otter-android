package app.ottermsg.core.network

data class EndpointStat(val lastSuccessMs: Long = 0, val lastFailureMs: Long = 0, val rttMs: Long = 0, val failures: Int = 0)

/** What worked recently, per candidate key (persisted by core:data). */
interface EndpointStats {
    suspend fun get(key: String): EndpointStat?
    suspend fun success(key: String, rttMs: Long, nowMs: Long)
    suspend fun failure(key: String, nowMs: Long)
}

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
}
