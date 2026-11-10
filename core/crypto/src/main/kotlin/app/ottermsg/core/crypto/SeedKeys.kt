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
}
