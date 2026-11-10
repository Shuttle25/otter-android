package app.ottermsg.core.data.account

import app.ottermsg.core.crypto.Ed25519
import org.signal.libsignal.protocol.IdentityKeyPair

class LocalAccount(
    val userId: ByteArray, val username: String, val displayName: String,
    val seed: ByteArray, val accountKey: Ed25519.KeyPair, val identity: IdentityKeyPair, val registrationId: Int,
)
