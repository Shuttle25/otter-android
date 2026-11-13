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
    /** "reset after N KB" count in the current 24 h window (D73). */
    @ColumnInfo(name = "throttle_count", defaultValue = "0") val throttleCount: Int = 0,
    @ColumnInfo(name = "throttle_window_start_ms", defaultValue = "0") val throttleWindowStartMs: Long = 0,
    /** Ordered last until then (D73). */
    @ColumnInfo(name = "demoted_until_ms", defaultValue = "0") val demotedUntilMs: Long = 0,
)
