package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Connectivity log (D74): one row per run of identical outcomes; stays on the phone. */
@Entity(tableName = "net_log", indices = [Index(value = ["node_label", "endpoint", "network"])])
data class NetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "node_label") val nodeLabel: String,
    val endpoint: String,
    /** wifi / mobile / other / none */
    val network: String,
    /** Mobile operator name (mobile only), else empty. */
    val operator: String,
    /** FailureClass wire name. */
    val result: String,
    val detail: String,
    @ColumnInfo(name = "rtt_ms") val rttMs: Long,
    val count: Int,
    @ColumnInfo(name = "first_ms") val firstMs: Long,
    @ColumnInfo(name = "last_ms") val lastMs: Long,
)
