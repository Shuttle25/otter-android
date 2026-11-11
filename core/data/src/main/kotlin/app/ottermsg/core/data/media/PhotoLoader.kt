package app.ottermsg.core.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.model.AttachmentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** Decodes photos into memory only (D46): decrypts the encrypted original and samples it down to the size asked for. */
@Singleton
class PhotoLoader @Inject constructor(private val db: OtterDatabase, private val files: MediaFiles) {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The photo of a message at roughly [maxPx] long edge (at most 2× larger), or null if it isn't on the phone. */
    suspend fun load(localId: Long, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = "$localId:$maxPx"
        cache.get(k)?.let { return@withContext it }
        val a = db.attachmentDao().forMessage(localId) ?: return@withContext null
        if (a.state != AttachmentState.DOWNLOADED && a.state != AttachmentState.UPLOADED && a.state != AttachmentState.UPLOADING &&
            a.state != AttachmentState.UPLOAD_FAILED) return@withContext null
        val f = files.file(a.file ?: return@withContext null)
        if (!f.exists()) return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BlobCipher.decrypt(f, a.key, a.blobKey).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BlobCipher.decrypt(f, a.key, a.blobKey).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()?.also { cache.put(k, it) }
    }

    /** Decoded JPEG bytes of the photo (gallery save). */
    suspend fun jpeg(localId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val a = db.attachmentDao().forMessage(localId) ?: return@withContext null
        val f = files.file(a.file ?: return@withContext null)
        if (!f.exists() || (a.state != AttachmentState.DOWNLOADED && a.state != AttachmentState.UPLOADED &&
                a.state != AttachmentState.UPLOADING && a.state != AttachmentState.UPLOAD_FAILED)) return@withContext null
        runCatching { BlobCipher.decrypt(f, a.key, a.blobKey).use { it.readBytes() } }.getOrNull()
    }

    fun thumb(bytes: ByteArray?): Bitmap? = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }

    fun clear() = cache.evictAll()
}
