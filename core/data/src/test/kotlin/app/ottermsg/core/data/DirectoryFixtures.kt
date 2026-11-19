package app.ottermsg.core.data

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.directory.Membership
import app.ottermsg.core.signal.IdentityKeys
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.ByteString
import java.security.MessageDigest

/** Signed directory entries with invite chains for M9.5 tests (the same shapes as DirectorySyncTest's helpers). */
class DirectoryFixtures(private val node: FakeNode) {
    fun acc(seed: Byte) = SeedKeys.accountKeyPair(ByteArray(32) { seed })
    fun uid(seed: Byte): ByteArray = Ids.id16(acc(seed).publicKey)
    private fun bs(b: ByteArray) = ByteString.copyFrom(b)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private val networkId get() = Ids.id16(node.network.publicKey)

    private fun invite(signer: Ed25519.KeyPair, author: ByteArray, secret: ByteArray, createdMs: Long): Record {
        val inv = Invite.newBuilder().setInviteId(bs(sha256(secret))).setExpiresMs(createdMs + Membership.MAX_INVITE_TTL_MS)
            .setRedeemPub(bs(InviteKeys.redeemKey(secret).publicKey)).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.INVITE, author, inv.toByteArray(), createdMs = createdMs), signer, node.ctx)
    }

    private fun chained(seed: Byte, name: String, inv: Record, secret: ByteArray, listed: Boolean): Record {
        val acc = acc(seed)
        val id = IdentityKeys.fromSeed(ByteArray(32) { seed })
        val uid = Ids.id16(acc.publicKey)
        val e = DirectoryEntry.newBuilder().setUserId(bs(uid)).setUsername(name).setDisplayName(name.uppercase())
            .setAccountPub(bs(acc.publicKey)).setIdentityPub(bs(id.publicKey.serialize()))
            .setIdentitySig(bs(IdentityKeys.bindAccount(id, acc.publicKey))).setVersion(1).setRegisteredMs(1000)
            .setListed(listed).setInviteRecord(inv.toByteString())
            .setInviteProof(bs(InviteKeys.redeemKey(secret).sign(InviteKeys.redeemMessage(sha256(secret), uid, acc.publicKey)))).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.DIRECTORY_ENTRY, uid, e.toByteArray()), acc, node.ctx)
    }

    /** An entry invited by the network key (a bootstrap link); [listed] is the signed catalog flag (D136). */
    fun member(seed: Byte, name: String, listed: Boolean = false): Record {
        val s = ByteArray(32) { (seed + 100).toByte() }
        return chained(seed, name, invite(node.network, networkId, s, 1), s, listed)
    }

    /** An entry invited by the account [by] (a test seed's key pair or a registered graph's account key). */
    fun invitedBy(by: Ed25519.KeyPair, seed: Byte, name: String, listed: Boolean = false, createdMs: Long = 10): Record {
        val s = ByteArray(32) { (seed + 100).toByte() }
        return chained(seed, name, invite(by, Ids.id16(by.publicKey), s, createdMs), s, listed)
    }
    fun invitedBy(by: Byte, seed: Byte, name: String, listed: Boolean = false): Record = invitedBy(acc(by), seed, name, listed)
}
