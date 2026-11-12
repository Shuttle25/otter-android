package app.ottermsg.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

class BackupKeysTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun argon2idMatchesRfc9106() {
        // RFC 9106 §5.3: Argon2id, m=32 KiB, t=3, p=4, password 32×01, salt 16×02, secret 8×03, ad 12×04
        val tag = BackupKeys.argon2id(ByteArray(32) { 1 }, ByteArray(16) { 2 }, mKib = 32, t = 3, p = 4, outLen = 32,
            secret = ByteArray(8) { 3 }, ad = ByteArray(12) { 4 })
        assertArrayEquals(hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"), tag)
    }

    @Test fun derivesSeparateKeysAndVerifier() {
        val salt = ByteArray(16) { 7 }
        val k = BackupKeys.derive("корова-лодка-зима-7", salt, mKib = 1024, t = 1, p = 1) // small params: fast test
        assertEquals(32, k.backupKey.size); assertEquals(32, k.authSecret.size)
        assertFalse(k.backupKey.contentEquals(k.authSecret))
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(k.authSecret), k.verifier)
        val again = BackupKeys.derive("корова-лодка-зима-7", salt, mKib = 1024, t = 1, p = 1)
        assertArrayEquals(k.backupKey, again.backupKey)
        val other = BackupKeys.derive("корова-лодка-зима-8", salt, mKib = 1024, t = 1, p = 1)
        assertFalse(k.backupKey.contentEquals(other.backupKey))
        assertEquals(16, BackupKeys.newSalt().size)
    }
}
