package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Connection history of one endpoint candidate (key = url + pins hash). */
@Entity(tableName = "endpoint_stats")
data class EndpointStatEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "last_success_ms") val lastSuccessMs: Long = 0,
    @ColumnInfo(name = "last_failure_ms") val lastFailureMs: Long = 0,
    @ColumnInfo(name = "rtt_ms") val rttMs: Long = 0,
    val failures: Int = 0,
)
