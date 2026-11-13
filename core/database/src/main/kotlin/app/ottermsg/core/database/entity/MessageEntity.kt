package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.ottermsg.core.database.model.MessageKind

@Entity(tableName = "messages",
    indices = [Index(value = ["message_id"], unique = true), Index(value = ["conversation_id", "sent_ms"])])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "local_id") val localId: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: ByteArray,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "sender_id") val senderId: ByteArray,
    val outgoing: Boolean,
    @ColumnInfo(name = "sent_ms") val sentMs: Long,
    @ColumnInfo(name = "received_ms") val receivedMs: Long,
    val body: String,
    val status: Int,
    val kind: Int = MessageKind.TEXT,
    @ColumnInfo(name = "read_receipt_sent") val readReceiptSent: Boolean = false,
    /** kind = SYSTEM_GROUP: encoded GroupEvent (rendered by the UI, never stored as text). */
    @ColumnInfo(name = "system_data") val systemData: ByteArray? = null,
    /** kind = CALL: CallOutcome.code (D80); 0 otherwise. Local only, never sent. */
    @ColumnInfo(name = "call_outcome", defaultValue = "0") val callOutcome: Int = 0,
    @ColumnInfo(name = "call_duration_s", defaultValue = "0") val callDurationS: Long = 0,
)
