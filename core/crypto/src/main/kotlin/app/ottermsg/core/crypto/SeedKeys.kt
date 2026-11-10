package app.ottermsg.core.crypto

import com.google.crypto.tink.subtle.Hkdf

/** Keys derived from the 32-byte recovery seed. Must match Go `keys.DeriveAccountKey`. */
object SeedKeys {
    const val INFO_ACCOUNT = "otter/account/v1"

    fun accountKeyPair(seed: ByteArray): Ed25519.KeyPair {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val k = Hkdf.computeHkdf("HMACSHA256", seed, ByteArray(0), INFO_ACCOUNT.toByteArray(), 32)
        return Ed25519.keyPairFromSeed(k)
    }

    const val INFO_IDENTITY = "otter/identity/v1"

    /** 32-byte libsignal identity private key (Curve25519); public = 0x05 || X25519(priv). */
    fun identityPrivate(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "seed must be 32 bytes" }
        return Hkdf.computeHkdf("HMACSHA256", seed, ByteArray(0), INFO_IDENTITY.toByteArray(), 32)
    }
}
