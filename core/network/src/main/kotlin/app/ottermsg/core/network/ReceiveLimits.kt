package app.ottermsg.core.network

import java.io.IOException

/**
 * What a node may send before the phone drops it (C17, D93). Legitimate maxima: `apk.get` = 1 MiB + header;
 * `envelopes` ≤ 768 KiB + one record (server 0.9.0); `blob.get` = 256 KiB.
 */
object ReceiveLimits {
    const val MAX_FRAME = 4 shl 20
    const val MAX_QUEUED_EVENT_BYTES = 16L shl 20
    const val BLOB_CHUNK = 256 * 1024 // == BlobCipher.CHUNK: one slice of the ciphertext file
    const val APK_CHUNK = 1 shl 20
    const val APK_MAX = 100L shl 20
}

/** A node broke a receive limit; the connection is dropped and the other nodes are tried. */
class OversizeFrame(what: String, size: Long, limit: Long) : IOException("$what: $size bytes > $limit")

/** The [OversizeFrame] anywhere in this cause chain, if any. */
fun Throwable?.oversize(): OversizeFrame? = generateSequence(this) { it.cause }.filterIsInstance<OversizeFrame>().firstOrNull()
