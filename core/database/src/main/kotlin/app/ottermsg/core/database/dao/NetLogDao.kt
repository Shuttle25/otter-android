package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.ottermsg.core.database.entity.NetLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class NetLogDao {
    @Query("SELECT * FROM net_log WHERE node_label = :label AND endpoint = :endpoint AND network = :network ORDER BY id DESC LIMIT 1")
    abstract suspend fun latest(label: String, endpoint: String, network: String): NetLogEntity?

    @Insert abstract suspend fun insert(e: NetLogEntity): Long

    @Query("UPDATE net_log SET count = count + 1, last_ms = :now, detail = :detail, rtt_ms = :rtt, operator = :operator WHERE id = :id")
    abstract suspend fun bump(id: Long, now: Long, detail: String, rtt: Long, operator: String)

    @Query("DELETE FROM net_log WHERE id NOT IN (SELECT id FROM net_log ORDER BY id DESC LIMIT :max)")
    abstract suspend fun trim(max: Int)

    /** Newest first. */
    @Query("SELECT * FROM net_log ORDER BY last_ms DESC, id DESC")
    abstract fun observe(): Flow<List<NetLogEntity>>

    @Query("SELECT * FROM net_log ORDER BY last_ms DESC, id DESC")
    abstract suspend fun all(): List<NetLogEntity>

    /** Merges a repeat of the latest outcome for the same node/endpoint/network, else inserts; keeps [max] rows. */
    @Transaction
    open suspend fun add(e: NetLogEntity, max: Int = MAX) {
        val last = latest(e.nodeLabel, e.endpoint, e.network)
        if (last != null && last.result == e.result) bump(last.id, e.lastMs, e.detail, e.rttMs, e.operator)
        else { insert(e); trim(max) }
    }

    companion object { const val MAX = 200 }
}
