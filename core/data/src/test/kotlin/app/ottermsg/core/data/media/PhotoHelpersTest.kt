package app.ottermsg.core.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoHelpersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun jpegFile(w: Int, h: Int, name: String = "src.jpg"): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        return File(context.cacheDir, name).also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    @Test fun rotatesScalesAndStripsMetadata() {
        val src = jpegFile(4000, 3000)
        ExifInterface(src).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setLatLong(55.75, 37.62)
            setAttribute(ExifInterface.TAG_MAKE, "Phone")
            saveAttributes()
        }
        val p = PhotoPrep().prepare(src)
        assertEquals(1920, p.width); assertEquals(2560, p.height)
        val out = File(context.cacheDir, "out.jpg").apply { writeBytes(p.jpeg) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, bounds)
        assertEquals(1920, bounds.outWidth); assertEquals(2560, bounds.outHeight)
        val exif = ExifInterface(out)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(0, exif.rotationDegrees)
        assertTrue("thumbnail ${p.thumb.size} bytes", p.thumb.size <= 4096)
        val t = BitmapFactory.decodeByteArray(p.thumb, 0, p.thumb.size)
        assertEquals(64, max(t.width, t.height))
    }

    @Test fun smallImageIsNotUpscaled() {
        val png = File(context.cacheDir, "small.png").also { f ->
            f.outputStream().use { Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val p = PhotoPrep().prepare(png)
        assertEquals(100, p.width); assertEquals(50, p.height)
    }

    @Test(expected = PhotoPrep.Unreadable::class) fun garbageIsUnreadable() {
        PhotoPrep().prepare(File(context.cacheDir, "junk").apply { writeText("not an image") })
    }

    @Test fun loaderDecodesBoundedAndCacheClearKeepsMedia() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OtterDatabase::class.java).allowMainThreadQueries().build()
        val files = MediaFiles(context)
        val jpeg = PhotoPrep().prepare(jpegFile(2000, 1000)).jpeg
        val name = files.newName()
        val key = BlobCipher.newKey(); val blobKey = ByteArray(32) { 3 }
        val sealed = BlobCipher.encrypt(ByteArrayInputStream(jpeg), files.file(name), key, blobKey)
        db.attachmentDao().insert(AttachmentEntity(messageLocalId = 9, blobKey = blobKey, key = key, digest = sealed.sha256,
            size = sealed.size, mime = "image/jpeg", width = 2000, height = 1000, thumb = null, file = name, state = AttachmentState.DOWNLOADED))
        val loader = PhotoLoader(db, files)
        val bmp = loader.load(9, 400)
        assertNotNull(bmp)
        assertTrue("decoded ${bmp!!.width}", max(bmp.width, bmp.height) in 400..800)
        assertNull(loader.load(10, 400))
        File(files.tempDir("pick"), "x.jpg").writeText("tmp")
        assertTrue(files.cacheBytes() > 0)
        files.clearCache()
        assertEquals(0L, files.cacheBytes())
        assertTrue(files.file(name).exists())
        assertTrue(files.mediaBytes() > 0)
        db.close()
    }
}
