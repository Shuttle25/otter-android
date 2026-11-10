package app.ottermsg.core.data.account

import app.ottermsg.core.database.key.KeyWrapper

/** Wraps/unwraps the recovery seed with its own device-bound key (Keystore alias `otter_seed_kek`). */
class SeedVault(private val wrapper: KeyWrapper) {
    fun wrap(seed: ByteArray): ByteArray = wrapper.wrap(seed)
    fun unwrap(enc: ByteArray): ByteArray = wrapper.unwrap(enc)
}
