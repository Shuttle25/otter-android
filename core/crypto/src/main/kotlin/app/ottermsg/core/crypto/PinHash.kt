package app.ottermsg.core.crypto

import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App-lock PIN hash (D121): salt(16) ‖ Argon2id(m 16 MiB, t 2, p 1) → 32 bytes, over the UTF-8 PIN. It lives in the
 * SQLCipher database, whose key is in the Keystore; the hash only slows guessing if that database is ever extracted
 * with its key. The caller's PIN array is not changed; the UTF-8 copy made here is wiped.
 */
object PinHash {
    const val SALT = 16
    const val OUT = 32
    private const val M_KIB = 16 * 1024
    private const val T = 2
    private const val P = 1

    fun hash(pin: CharArray, salt: ByteArray = ByteArray(SALT).also(SecureRandom()::nextBytes)): ByteArray {
        require(salt.size == SALT) { "salt must be $SALT bytes" }
        return salt.copyOf() + derive(pin, salt)
    }

    fun verify(pin: CharArray, stored: ByteArray): Boolean {
        if (stored.size != SALT + OUT) return false
        return MessageDigest.isEqual(derive(pin, stored.copyOfRange(0, SALT)), stored.copyOfRange(SALT, stored.size))
    }

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val buf = Charsets.UTF_8.encode(CharBuffer.wrap(pin))
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        if (buf.hasArray()) buf.array().fill(0)
        try {
            return BackupKeys.argon2id(bytes, salt, M_KIB, T, P, OUT)
        } finally {
            bytes.fill(0)
        }
    }
}
