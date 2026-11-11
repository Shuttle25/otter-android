package app.ottermsg.core.data.network

import app.ottermsg.core.database.dao.EndpointStatDao
import app.ottermsg.core.database.entity.EndpointStatEntity
import app.ottermsg.core.network.EndpointStat
import app.ottermsg.core.network.EndpointStats
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomEndpointStats @Inject constructor(private val dao: EndpointStatDao) : EndpointStats {
    private val mutex = Mutex() // read-modify-write per key

    override suspend fun get(key: String): EndpointStat? =
        dao.get(key)?.let { EndpointStat(it.lastSuccessMs, it.lastFailureMs, it.rttMs, it.failures) }

    override suspend fun success(key: String, rttMs: Long, nowMs: Long) = mutex.withLock {
        val s = dao.get(key) ?: EndpointStatEntity(key)
        dao.upsert(s.copy(lastSuccessMs = nowMs, rttMs = rttMs, failures = 0))
    }

    override suspend fun failure(key: String, nowMs: Long) = mutex.withLock {
        val s = dao.get(key) ?: EndpointStatEntity(key)
        dao.upsert(s.copy(lastFailureMs = nowMs, failures = s.failures + 1))
    }
}
