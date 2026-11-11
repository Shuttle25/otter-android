package app.ottermsg.core.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photo policy (D47): re-encode as JPEG, long edge ≤ 2560 px (never upscaled), orientation applied, quality 85,
 * **no metadata** (Bitmap.compress writes none, so EXIF/GPS never leave the phone); a ≤ 4 KiB 64 px thumbnail.
 */
class PhotoPrep @Inject constructor() {
    class Prepared(val jpeg: ByteArray, val width: Int, val height: Int, val thumb: ByteArray)
    class Unreadable : Exception("photo can't be decoded")

    fun prepare(source: File, maxEdge: Int = MAX_EDGE): Prepared {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Unreadable()
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val raw = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw Unreadable()
        val degrees = runCatching { ExifInterface(source).rotationDegrees }.getOrDefault(0)
        val rotated = rotate(raw, degrees)
        val scaled = scaleDown(rotated, maxEdge)
        return Prepared(compress(scaled, QUALITY), scaled.width, scaled.height, thumbnail(scaled))
    }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return b
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun scaleDown(b: Bitmap, maxEdge: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= maxEdge) return b
        val f = maxEdge.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt(), (b.height * f).roundToInt(), true)
    }

    private fun compress(b: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun thumbnail(b: Bitmap): ByteArray {
        val small = scaleDown(b, THUMB_EDGE)
        for (q in intArrayOf(60, 45, 30, 15)) {
            val out = compress(small, q)
            if (out.size <= THUMB_MAX) return out
        }
        return compress(scaleDown(small, THUMB_EDGE / 2), 15)
    }

    companion object {
        const val MAX_EDGE = 2560
        const val QUALITY = 85
        const val THUMB_EDGE = 64
        const val THUMB_MAX = 4096
    }
}
