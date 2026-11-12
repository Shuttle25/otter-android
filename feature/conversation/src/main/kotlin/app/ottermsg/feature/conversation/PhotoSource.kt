package app.ottermsg.feature.conversation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState

/** Image access for photo bubbles: composables never touch Room or decryption (guide §13). */
interface PhotoSource {
    suspend fun load(localId: Long, maxPx: Int): ImageBitmap?
    suspend fun thumb(localId: Long): ImageBitmap?
}

object NoPhotos : PhotoSource {
    override suspend fun load(localId: Long, maxPx: Int): ImageBitmap? = null
    override suspend fun thumb(localId: Long): ImageBitmap? = null
}

val LocalPhotoSource = staticCompositionLocalOf<PhotoSource> { NoPhotos }

private const val CHUNK = 256 * 1024

/** Attachment row → what the bubble shows. */
fun photoUi(a: AttachmentEntity): PhotoUi {
    val chunks = ((a.size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
    val progress = (a.doneChunks.toFloat() / chunks).coerceIn(0f, 1f)
    val state = when (a.state) {
        AttachmentState.UPLOADING -> PhotoState.Uploading
        AttachmentState.REMOTE -> PhotoState.Remote
        AttachmentState.QUEUED -> PhotoState.Downloading
        AttachmentState.FAILED -> PhotoState.Failed
        AttachmentState.GONE -> PhotoState.Gone
        AttachmentState.NOT_IN_BACKUP -> PhotoState.NotInBackup
        else -> PhotoState.Ready // UPLOADED, UPLOAD_FAILED (the original is on the phone), DOWNLOADED
    }
    return PhotoUi(a.width, a.height, state,
        progress = if (state == PhotoState.Uploading || state == PhotoState.Downloading) progress else null)
}
