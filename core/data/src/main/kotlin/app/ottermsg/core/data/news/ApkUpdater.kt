package app.ottermsg.core.data.news

import app.ottermsg.core.data.di.CacheDir
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.network.ReceiveLimits
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.AppUpdate
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateResult {
    data class Ready(val file: File) : UpdateResult
    data object Corrupt : UpdateResult
    data object NotOnServer : UpdateResult
    data object Offline : UpdateResult
}

/**
 * Downloads an announced APK from the connected node (`apk.get`, 1 MiB chunks, D68) into `cacheDir/apk/<sha256>.part`,
 * resuming at a full-chunk boundary, then checks size and SHA-256 and renames it to `<sha256>.apk`. The signed size
 * must be 1 byte..100 MiB and every chunk exactly `min(1 MiB, size − offset)`, else `Corrupt` (C17, D93).
 * Network errors other than NOT_FOUND propagate (the caller shows «Нет связи с сервером»).
 */
/** What the conversation screen needs from [ApkUpdater] (faked in ViewModel tests). */
interface UpdateDownloader {
    /** [progress] gets 0..1; network errors other than NOT_FOUND are thrown. */
    suspend fun download(u: AppUpdate, progress: (Float) -> Unit): UpdateResult
}

@Singleton
class ApkUpdater @Inject constructor(private val online: OnlineApi, @param:CacheDir private val cacheDir: File) : UpdateDownloader {
    override suspend fun download(u: AppUpdate, progress: (Float) -> Unit): UpdateResult = withContext(Dispatchers.IO) {
        if (u.size <= 0 || u.size > ReceiveLimits.APK_MAX) return@withContext UpdateResult.Corrupt // C17
        val hex = u.sha256.toByteArray().joinToString("") { "%02x".format(it) }
        val dir = File(cacheDir, "apk").apply { mkdirs() }
        val done = File(dir, "$hex.apk")
        if (done.exists() && verify(done, u)) return@withContext UpdateResult.Ready(done)
        done.delete()
        dir.listFiles()?.filter { !it.name.startsWith(hex) }?.forEach { it.delete() } // older updates
        val part = File(dir, "$hex.part")
        val have = (part.length() / CHUNK) * CHUNK
        RandomAccessFile(part, "rw").use { it.setLength(have) }
        var index = (have / CHUNK).toInt()
        try {
            FileOutputStream(part, true).use { out ->
                while (index.toLong() * CHUNK < u.size) {
                    val api = online.current() ?: return@withContext UpdateResult.Offline
                    val c = api.apkGet(u.sha256.toByteArray(), index)
                    val want = minOf(CHUNK, u.size - index.toLong() * CHUNK)
                    if (c.data.size().toLong() != want) { // C17: the file never grows past the signed size
                        out.close(); part.delete()
                        return@withContext UpdateResult.Corrupt
                    }
                    out.write(c.data.toByteArray())
                    index++
                    progress((index.toLong() * CHUNK).coerceAtMost(u.size).toFloat() / u.size)
                }
            }
        } catch (e: RpcException) {
            if (e.status == Status.STATUS_NOT_FOUND) return@withContext UpdateResult.NotOnServer
            throw e
        }
        if (!verify(part, u)) { part.delete(); return@withContext UpdateResult.Corrupt }
        if (!part.renameTo(done)) return@withContext UpdateResult.Corrupt
        UpdateResult.Ready(done)
    }

    private fun verify(f: File, u: AppUpdate): Boolean {
        if (f.length() != u.size) return false
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().contentEquals(u.sha256.toByteArray())
    }

    companion object { const val CHUNK = 1L shl 20 }
}
