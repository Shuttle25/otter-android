package app.ottermsg.core.data.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Writes a decrypted photo to Pictures/Otter (the only place plaintext lands on storage, at the user's request). */
@Singleton
class GallerySaver @Inject constructor(@ApplicationContext private val context: Context, private val loader: PhotoLoader) {
    /** API 26–28 need WRITE_EXTERNAL_STORAGE for the public Pictures directory. */
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    suspend fun save(localId: Long): Boolean = withContext(Dispatchers.IO) {
        if (needsPermission()) return@withContext false
        val jpeg = loader.jpeg(localId) ?: return@withContext false
        val name = "Otter_${System.currentTimeMillis()}.jpg"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Otter")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val r = context.contentResolver
                val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                r.openOutputStream(uri)?.use { it.write(jpeg) } ?: return@runCatching false
                r.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Otter").also { it.mkdirs() }
                val f = File(dir, name)
                f.writeBytes(jpeg)
                MediaScannerConnection.scanFile(context, arrayOf(f.path), arrayOf("image/jpeg"), null)
                true
            }
        }.getOrDefault(false)
    }
}
