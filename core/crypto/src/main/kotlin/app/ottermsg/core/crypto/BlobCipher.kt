package app.ottermsg.core.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Encryption of blobs (photos) at rest and in transit (D46): Tink AES-256-GCM-HKDF streaming (HMAC-SHA256, 1 MiB
 * segments) with a fresh 32-byte key per blob and the blob key as associated data. The ciphertext file is what is
 * uploaded in 256 KiB chunks and what the phone keeps as the encrypted original.
 */
object BlobCipher {
    const val SEGMENT = 1 shl 20
    const val CHUNK = 256 * 1024

    class Sealed(val size: Long, val sha256: ByteArray)

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun aead(key: ByteArray) = AesGcmHkdfStreaming(key, "HmacSha256", 32, SEGMENT, 0)

    /** Encrypts [input] into [out]; returns the ciphertext size and SHA-256. */
    fun encrypt(input: InputStream, out: File, key: ByteArray, aad: ByteArray): Sealed {
        val md = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(out.outputStream().buffered(), md).use { o ->
            aead(key).newEncryptingStream(o, aad).use { input.copyTo(it) }
        }
        return Sealed(out.length(), md.digest())
    }

    /** Plaintext stream; reading fails with an IOException on a wrong key, wrong aad or tampered bytes. */
    fun decrypt(file: File, key: ByteArray, aad: ByteArray): InputStream =
        aead(key).newDecryptingStream(file.inputStream().buffered(), aad)

    /** SHA-256 of every [chunk]-sized piece of [file] (the hashes in BLOB_META). */
    fun chunkHashes(file: File, chunk: Int = CHUNK): List<ByteArray> = file.inputStream().buffered().use { s ->
        val buf = ByteArray(chunk)
        val out = ArrayList<ByteArray>()
        while (true) {
            val n = readFully(s, buf)
            if (n <= 0) break
            out += MessageDigest.getInstance("SHA-256").apply { update(buf, 0, n) }.digest()
        }
        out
    }

    /** Fills [buf] as far as the stream allows (InputStream.readNBytes needs API 33). */
    fun readFully(s: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        return n
    }

    fun sha256(file: File): ByteArray = file.inputStream().buffered().use { s ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        md.digest()
    }
}
