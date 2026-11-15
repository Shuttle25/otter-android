package app.ottermsg.core.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.model.AttachmentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** Decodes photos into memory only (D46): decrypts the encrypted original and samples it down to the size asked for. */
@Singleton
class PhotoLoader @Inject constructor(private val db: OtterDatabase, private val files: MediaFiles) {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Photos whose file exists but cannot be shown safely or at all (C21): «Фото повреждено». */
    private val corrupt: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    fun isCorrupt(localId: Long): Boolean = localId in corrupt

    /**
     * The photo of a message at roughly [maxPx] long edge (at most 2× larger, never above [MAX_DECODED_EDGE]), or null if
     * it isn't on the phone or can't be decoded safely (then [isCorrupt]). Bounds are read first: a source over
     * [MAX_SOURCE_PIXELS] or with no size is never decoded (D98).
     */
    suspend fun load(localId: Long, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = "$localId:$maxPx"
        cache.get(k)?.let { return@withContext it }
        val a = db.attachmentDao().forMessage(localId) ?: return@withContext null
        if (a.state != AttachmentState.DOWNLOADED && a.state != AttachmentState.UPLOADED && a.state != AttachmentState.UPLOADING &&
            a.state != AttachmentState.UPLOAD_FAILED) return@withContext null
        val f = files.file(a.file ?: return@withContext null)
        if (!f.exists()) return@withContext null
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BlobCipher.decrypt(f, a.key, a.blobKey).use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = sampleFor(bounds.outWidth, bounds.outHeight, maxPx) ?: return@runCatching null
            BlobCipher.decrypt(f, a.key, a.blobKey).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }.getOrNull()
        if (bmp == null) { corrupt += localId; return@withContext null }
        corrupt -= localId
        bmp.also { cache.put(k, it) }
    }

    /** Decoded JPEG bytes of the photo (gallery save). */
    suspend fun jpeg(localId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val a = db.attachmentDao().forMessage(localId) ?: return@withContext null
        val f = files.file(a.file ?: return@withContext null)
        if (!f.exists() || (a.state != AttachmentState.DOWNLOADED && a.state != AttachmentState.UPLOADED &&
                a.state != AttachmentState.UPLOADING && a.state != AttachmentState.UPLOAD_FAILED)) return@withContext null
        runCatching { BlobCipher.decrypt(f, a.key, a.blobKey).use { it.readBytes() } }.getOrNull()
    }

    /** The sender's thumbnail (≤ 16 KiB of JPEG, but its header can claim any size): bounded like a photo. */
    fun thumb(bytes: ByteArray?): Bitmap? = bytes?.let { b ->
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
            val s = sampleFor(bounds.outWidth, bounds.outHeight, THUMB_MAX_PX) ?: return@runCatching null
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull()
    }

    fun clear() = cache.evictAll()

    companion object {
        const val MAX_SOURCE_PIXELS = 100_000_000L
        const val MAX_DECODED_EDGE = 4096
        const val THUMB_MAX_PX = 256

        /** inSampleSize for a [w]×[h] source shown at about [maxPx] (≤ 2× larger, never above 4096); null = refuse (C21). */
        fun sampleFor(w: Int, h: Int, maxPx: Int): Int? {
            if (w <= 0 || h <= 0 || w.toLong() * h > MAX_SOURCE_PIXELS) return null
            val edge = max(w, h)
            var s = 1
            while (edge / (s * 2) >= minOf(maxPx, MAX_DECODED_EDGE)) s *= 2
            while (edge / s > MAX_DECODED_EDGE) s *= 2
            return s
        }
    }
}
