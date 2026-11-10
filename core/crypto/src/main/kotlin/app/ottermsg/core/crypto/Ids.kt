package app.ottermsg.core.crypto

import java.security.MessageDigest

object Ids {
    fun id16(publicKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16)
}
