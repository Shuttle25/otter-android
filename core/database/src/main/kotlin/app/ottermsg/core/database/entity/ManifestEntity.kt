package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "manifest")
data class ManifestEntity(
    @PrimaryKey val version: Long,
    val raw: ByteArray,
    @ColumnInfo(name = "received_ms", defaultValue = "0") val receivedMs: Long = 0,
)
