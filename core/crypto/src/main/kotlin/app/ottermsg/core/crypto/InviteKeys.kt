package app.ottermsg.core.crypto

import com.google.crypto.tink.subtle.Hkdf

/** The invite's one-time key (D100). Must match Go `keys.InviteRedeemKey` / `keys.RedeemMessage`. */
object InviteKeys {
    const val INFO_REDEEM = "otter/invite-redeem/v1"
    private const val CONTEXT = "otter-invite-redeem-v1"

    fun redeemKey(secret: ByteArray): Ed25519.KeyPair {
        require(secret.size == 32) { "invite secret must be 32 bytes" }
        return Ed25519.keyPairFromSeed(Hkdf.computeHkdf("HMACSHA256", secret, ByteArray(0), INFO_REDEEM.toByteArray(), 32))
    }

    fun redeemMessage(inviteId: ByteArray, userId: ByteArray, accountPub: ByteArray): ByteArray =
        CONTEXT.toByteArray() + inviteId + userId + accountPub
}
