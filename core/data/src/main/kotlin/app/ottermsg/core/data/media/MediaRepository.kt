package app.ottermsg.core.data.media

import android.graphics.Bitmap
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
    fun galleryNeedsPermission(): Boolean
    suspend fun saveToGallery(localId: Long): Boolean
}

@Singleton
class MediaRepository @Inject constructor(
    private val db: OtterDatabase, private val loader: PhotoLoader, private val gallery: GallerySaver, private val media: MediaTransfers,
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
    override fun galleryNeedsPermission(): Boolean = gallery.needsPermission()
    override suspend fun saveToGallery(localId: Long): Boolean = gallery.save(localId)
}
