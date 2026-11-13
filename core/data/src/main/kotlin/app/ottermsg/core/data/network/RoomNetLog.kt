package app.ottermsg.core.data.network

import app.ottermsg.core.data.Clock
import app.ottermsg.core.database.dao.NetLogDao
import app.ottermsg.core.database.entity.NetLogEntity
import app.ottermsg.core.network.NetLog
import app.ottermsg.core.network.NetLogEntry
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's connectivity log in Room (D74): merged runs, newest 200 rows, never uploaded. */
@Singleton
class RoomNetLog @Inject constructor(private val dao: NetLogDao, private val net: CurrentNetwork, private val clock: Clock) : NetLog {
    override suspend fun add(e: NetLogEntry) {
        val (network, operator) = net.current()
        val now = clock.now()
        dao.add(NetLogEntity(nodeLabel = e.nodeLabel, endpoint = e.endpoint, network = network, operator = operator,
            result = e.result.wire, detail = e.detail.take(200), rttMs = e.rttMs, count = 1, firstMs = now, lastMs = now))
    }
}
