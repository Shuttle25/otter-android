package app.ottermsg.core.crypto

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

object Ed25519 {
    class KeyPair(val seed: ByteArray, val publicKey: ByteArray) {
        fun sign(data: ByteArray): ByteArray = Ed25519Sign(seed).sign(data)
    }

    fun keyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == 32)
        return KeyPair(seed.copyOf(), Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey)
    }

    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        try { Ed25519Verify(publicKey).verify(signature, data); true } catch (e: GeneralSecurityException) { false }
}
