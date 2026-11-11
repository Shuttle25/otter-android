package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A photo of a message (D46/D48). The ciphertext file in filesDir/media is the phone's encrypted original. */
@Entity(tableName = "attachments", indices = [Index(value = ["message_local_id"], unique = true), Index("state")])
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "message_local_id") val messageLocalId: Long,
    @ColumnInfo(name = "blob_key") val blobKey: ByteArray,
    @ColumnInfo(name = "key") val key: ByteArray,
    @ColumnInfo(name = "digest") val digest: ByteArray,
    /** Ciphertext bytes. */
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "mime") val mime: String,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "thumb") val thumb: ByteArray?,
    /** File name in filesDir/media (outgoing: from the start; incoming: reserved, complete once DOWNLOADED). */
    @ColumnInfo(name = "file") val file: String?,
    @ColumnInfo(name = "state") val state: Int,
    @ColumnInfo(name = "done_chunks") val doneChunks: Int = 0,
    /** Incoming: may download without a tap (policy at receive time). */
    @ColumnInfo(name = "auto") val auto: Boolean = false,
    /** Outgoing: the signed BLOB_META. */
    @ColumnInfo(name = "meta_record") val metaRecord: ByteArray? = null,
    /** Incoming: BLOB_FETCHED not sent yet. */
    @ColumnInfo(name = "ack_pending") val ackPending: Boolean = false,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_ms") val nextAttemptMs: Long = 0,
)
