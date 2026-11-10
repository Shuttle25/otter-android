package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "signal_kv", primaryKeys = ["table_id", "k"])
data class SignalKvEntity(@ColumnInfo(name = "table_id") val tableId: Int, val k: String, val v: ByteArray)
