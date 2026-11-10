package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations", indices = [Index(value = ["peer_user_id"], unique = true)])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "peer_user_id") val peerUserId: ByteArray,
    val title: String,
    @ColumnInfo(name = "last_activity_ms") val lastActivityMs: Long,
    val unread: Int = 0,
    val draft: String = "",
)
