package app.ottermsg.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

class BlobCipherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val aad = ByteArray(32) { it.toByte() }

    private fun roundtrip(size: Int) {
        val plain = Random.nextBytes(size)
        val key = BlobCipher.newKey()
        val f = tmp.newFile()
        val sealed = BlobCipher.encrypt(plain.inputStream(), f, key, aad)
        assertEquals(f.length(), sealed.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(f.readBytes()), sealed.sha256)
        assertArrayEquals(sealed.sha256, BlobCipher.sha256(f))
        assertArrayEquals(plain, BlobCipher.decrypt(f, key, aad).use { it.readBytes() })
        val hashes = BlobCipher.chunkHashes(f)
        assertEquals(((f.length() + BlobCipher.CHUNK - 1) / BlobCipher.CHUNK).toInt(), hashes.size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(f.readBytes().copyOfRange(0, minOf(BlobCipher.CHUNK, f.length().toInt()))), hashes[0])
    }

    @Test fun oneByte() = roundtrip(1)
    @Test fun exactlyOneSegment() = roundtrip(1 shl 20)
    @Test fun twoAndAHalfMiB() = roundtrip(5 shl 19)

    private fun fails(block: () -> Unit): Boolean = try { block(); false } catch (e: IOException) { true } catch (e: java.security.GeneralSecurityException) { true }

    @Test fun tamperingOrWrongContextFails() {
        val plain = Random.nextBytes(300_000)
        val key = BlobCipher.newKey()
        val f = tmp.newFile()
        BlobCipher.encrypt(plain.inputStream(), f, key, aad)
        assertTrue("wrong aad", fails { BlobCipher.decrypt(f, key, ByteArray(32)).use { it.readBytes() } })
        assertTrue("wrong key", fails { BlobCipher.decrypt(f, BlobCipher.newKey(), aad).use { it.readBytes() } })
        val bytes = f.readBytes(); bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        val t = File(tmp.root, "t"); t.writeBytes(bytes)
        assertTrue("tampered", fails { BlobCipher.decrypt(t, key, aad).use { it.readBytes() } })
        assertFalse(BlobCipher.newKey().contentEquals(BlobCipher.newKey()))
        assertEquals(32, BlobCipher.newKey().size)
    }
}
