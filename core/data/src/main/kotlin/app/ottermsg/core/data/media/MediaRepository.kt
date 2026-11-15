package app.ottermsg.core.data.media

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the conversation and viewer screens need from photos (a fake in UI tests). */
interface PhotoStore {
    fun observe(conversationId: Long): Flow<Map<Long, AttachmentEntity>>
    fun observeOne(localId: Long): Flow<AttachmentEntity?>
    /** REMOTE/FAILED → download now. */
    suspend fun download(localId: Long)
    suspend fun load(localId: Long, maxPx: Int): Bitmap?
    suspend fun thumb(localId: Long): Bitmap?
    /** The photo is on the phone but cannot be decoded safely (C21): «Фото повреждено». */
    suspend fun corrupt(localId: Long): Boolean = false
    fun galleryNeedsPermission(): Boolean
    suspend fun saveToGallery(localId: Long): Boolean
    /** Copies a picked image into cacheDir/pick at once (the URI grant may not survive process death). */
    suspend fun importPicked(uri: Uri): File?
    /** A fresh file in cacheDir/camera for TakePicture (shared through the FileProvider). */
    fun newCameraFile(): File
    fun discard(file: File)
}

@Singleton
class MediaRepository @Inject constructor(
    private val db: OtterDatabase, private val loader: PhotoLoader, private val gallery: GallerySaver, private val media: MediaTransfers,
    private val files: MediaFiles,
) : PhotoStore {
    private val dao = db.attachmentDao()

    override fun observe(conversationId: Long): Flow<Map<Long, AttachmentEntity>> =
        dao.observeConversation(conversationId).map { list -> list.associateBy { it.messageLocalId } }

    override fun observeOne(localId: Long): Flow<AttachmentEntity?> = dao.observeMessage(localId)

    override suspend fun download(localId: Long) {
        val a = dao.forMessage(localId) ?: return
        if (a.state == AttachmentState.REMOTE || a.state == AttachmentState.FAILED) {
            dao.update(a.copy(state = AttachmentState.QUEUED, attempts = 0, nextAttemptMs = 0))
            media.kick()
        }
    }

    override suspend fun load(localId: Long, maxPx: Int): Bitmap? = loader.load(localId, maxPx)
    override suspend fun thumb(localId: Long): Bitmap? = loader.thumb(dao.forMessage(localId)?.thumb)
    override suspend fun corrupt(localId: Long): Boolean = loader.isCorrupt(localId)
    override fun galleryNeedsPermission(): Boolean = gallery.needsPermission()
    override suspend fun saveToGallery(localId: Long): Boolean = gallery.save(localId)

    override suspend fun importPicked(uri: Uri): File? = withContext(Dispatchers.IO) {
        val out = File(files.tempDir("pick"), UUID.randomUUID().toString() + ".img")
        runCatching {
            files.resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
            out
        }.getOrNull()?.takeIf { it.length() > 0 }
    }

    override fun newCameraFile(): File = File(files.tempDir("camera"), UUID.randomUUID().toString() + ".jpg")
    override fun discard(file: File) { file.delete() }
}
