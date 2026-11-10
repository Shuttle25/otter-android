package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seen_envelopes")
data class SeenEnvelopeEntity(@PrimaryKey @ColumnInfo(name = "record_key") val recordKey: ByteArray, @ColumnInfo(name = "until_ms") val untilMs: Long)
