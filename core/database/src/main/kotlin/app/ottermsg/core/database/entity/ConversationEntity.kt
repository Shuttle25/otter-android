package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations", indices = [Index(value = ["peer_user_id"], unique = true), Index(value = ["group_id"], unique = true)])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Direct conversations only. */
    @ColumnInfo(name = "peer_user_id") val peerUserId: ByteArray? = null,
    val title: String,
    @ColumnInfo(name = "last_activity_ms") val lastActivityMs: Long,
    val unread: Int = 0,
    val draft: String = "",
    /** Group conversations only. */
    @ColumnInfo(name = "group_id") val groupId: ByteArray? = null,
    @ColumnInfo(defaultValue = "0") val type: Int = ConversationType.DIRECT,
)

object ConversationType { const val DIRECT = 0; const val GROUP = 1 }
