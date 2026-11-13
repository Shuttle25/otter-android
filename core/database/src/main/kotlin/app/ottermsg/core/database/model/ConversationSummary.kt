package app.ottermsg.core.database.model

import androidx.room.ColumnInfo

data class ConversationSummary(
    val id: Long, val title: String, @ColumnInfo(name = "peer_user_id") val peerUserId: ByteArray?,
    val unread: Int, @ColumnInfo(name = "last_activity_ms") val lastActivityMs: Long,
    @ColumnInfo(name = "last_body") val lastBody: String?, @ColumnInfo(name = "last_status") val lastStatus: Int?,
    @ColumnInfo(name = "last_outgoing") val lastOutgoing: Boolean?, @ColumnInfo(name = "last_kind") val lastKind: Int?,
    @ColumnInfo(name = "group_id") val groupId: ByteArray? = null,
    val family: Boolean = false,
    @ColumnInfo(name = "last_sender_id") val lastSenderId: ByteArray? = null,
    @ColumnInfo(name = "last_system_data") val lastSystemData: ByteArray? = null,
    /** ConversationType. */
    val type: Int = app.ottermsg.core.database.entity.ConversationType.DIRECT,
    /** Last message is a call line (kind CALL): its outcome and duration. */
    @ColumnInfo(name = "last_call_outcome") val lastCallOutcome: Int? = null,
    @ColumnInfo(name = "last_call_duration_s") val lastCallDurationS: Long? = null,
) {
    /** Stable avatar seed: peer user id or group id. */
    val seed: ByteArray get() = peerUserId ?: groupId ?: ByteArray(0)
}
