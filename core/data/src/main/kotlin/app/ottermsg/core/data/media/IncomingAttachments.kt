package app.ottermsg.core.data.media

import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.proto.v1.Attachment
import app.ottermsg.proto.v1.DataMessage

/** Receiving side of photos (D46/D47): what an incoming attachment must look like and how it is stored. */
object IncomingAttachments {
    const val MAX_SIZE = 100L shl 20

    /** The photo of [d], or null (anything else in attachments is ignored; the text still shows). */
    fun photoOrNull(d: DataMessage): Attachment? = d.attachmentsList.firstOrNull { a ->
        a.blobKey.size() == 32 && a.key.size() == 32 && a.digest.size() == 32 && a.size in 1..MAX_SIZE &&
            a.mime.startsWith("image/") && a.thumbnail.size() <= 16 * 1024
    }

    /** Stores [a] for message [localId] as REMOTE with a reserved file name. Call inside the receiver's transaction. */
    suspend fun insert(db: OtterDatabase, files: MediaFiles, localId: Long, a: Attachment, auto: Boolean) {
        db.attachmentDao().insert(AttachmentEntity(messageLocalId = localId, blobKey = a.blobKey.toByteArray(), key = a.key.toByteArray(),
            digest = a.digest.toByteArray(), size = a.size, mime = a.mime, width = a.width, height = a.height,
            thumb = a.thumbnail.toByteArray().takeIf { it.isNotEmpty() }, file = files.newName(), state = AttachmentState.REMOTE, auto = auto))
    }
}

/** Receivers' handle on incoming photos: stores them with the current auto-download policy and wakes the transfers. */
@javax.inject.Singleton
class IncomingPhotos @javax.inject.Inject constructor(
    private val db: OtterDatabase, private val files: MediaFiles, private val settings: app.ottermsg.core.data.settings.Settings,
    private val media: MediaTransfers,
) {
    suspend fun store(localId: Long, a: Attachment) =
        IncomingAttachments.insert(db, files, localId, a, auto = settings.photoAutoDownload() != app.ottermsg.core.data.settings.Settings.AUTO_MANUAL)

    fun kick() = media.kick()
}
