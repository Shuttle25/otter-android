package app.ottermsg.core.data.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where media lives (D46): encrypted originals in filesDir/media (never cleared by "Очистить кэш", D24); picker and
 * camera copies in cacheDir (cleared).
 */
@Singleton
open class MediaFiles @Inject constructor(@ApplicationContext private val context: Context) {
    val resolver: android.content.ContentResolver get() = context.contentResolver

    open val mediaDir: File get() = File(context.filesDir, "media").also { it.mkdirs() }

    fun newName(): String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) } + ".otb"
    fun file(name: String) = File(mediaDir, name)
    /** Download in progress; renamed to [file] after the digest check. */
    fun part(name: String) = File(mediaDir, "$name.part")
    /** Scratch directory under cacheDir: "pick" (gallery copies), "camera" (FileProvider). */
    fun tempDir(kind: String): File = File(context.cacheDir, kind).also { it.mkdirs() }

    fun cacheBytes(): Long = size(context.cacheDir)
    fun mediaBytes(): Long = size(mediaDir)

    /** Empties cacheDir; never touches filesDir/media. */
    fun clearCache() { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }

    private fun size(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { size(it) } ?: 0
}
