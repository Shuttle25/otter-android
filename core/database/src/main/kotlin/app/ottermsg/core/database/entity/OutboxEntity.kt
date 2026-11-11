package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** content_bytes is plaintext Content (the DB is SQLCipher-encrypted); record_bytes is set once
 *  encrypted+signed so retries resend the identical record. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recipient_user_id") val recipientUserId: ByteArray,
    @ColumnInfo(name = "content_bytes") val contentBytes: ByteArray,
    @ColumnInfo(name = "message_local_id") val messageLocalId: Long?,
    val urgent: Boolean,
    @ColumnInfo(name = "record_bytes") val recordBytes: ByteArray? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_ms") val nextAttemptMs: Long = 0,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
    /** Set once a data message was accepted by a node; kept until a receipt arrives (resend after 24 h). */
    @ColumnInfo(name = "sent_ms") val sentMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val resent: Boolean = false,
    /** Group message: one SENDER_KEY envelope to the frozen recipient set; [recipientUserId] is empty. */
    @ColumnInfo(name = "group_id") val groupId: ByteArray? = null,
)
