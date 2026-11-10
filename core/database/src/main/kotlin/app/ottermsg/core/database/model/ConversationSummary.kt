package app.ottermsg.core.database.model

import androidx.room.ColumnInfo

data class ConversationSummary(
    val id: Long, val title: String, @ColumnInfo(name = "peer_user_id") val peerUserId: ByteArray,
    val unread: Int, @ColumnInfo(name = "last_activity_ms") val lastActivityMs: Long,
    @ColumnInfo(name = "last_body") val lastBody: String?, @ColumnInfo(name = "last_status") val lastStatus: Int?,
    @ColumnInfo(name = "last_outgoing") val lastOutgoing: Boolean?, @ColumnInfo(name = "last_kind") val lastKind: Int?,
)
