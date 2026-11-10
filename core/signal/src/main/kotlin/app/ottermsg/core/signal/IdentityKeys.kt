package app.ottermsg.core.signal

import app.ottermsg.core.crypto.SeedKeys
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey

object IdentityKeys {
    fun fromSeed(seed: ByteArray): IdentityKeyPair {
        val priv = ECPrivateKey(SeedKeys.identityPrivate(seed))
        return IdentityKeyPair(IdentityKey(priv.getPublicKey()), priv)
    }

    /** DirectoryEntry.identity_sig: XEdDSA by the identity key over the Ed25519 account public key. */
    fun bindAccount(identity: IdentityKeyPair, accountPub: ByteArray): ByteArray = identity.privateKey.calculateSignature(accountPub)

    fun verifyBinding(identityPub: ByteArray, accountPub: ByteArray, signature: ByteArray): Boolean =
        runCatching { IdentityKey(identityPub).publicKey.verifySignature(accountPub, signature) }.getOrDefault(false)
}
