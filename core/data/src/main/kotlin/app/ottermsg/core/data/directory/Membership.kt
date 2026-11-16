package app.ottermsg.core.data.directory

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SigContext
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.InviteRights
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Revocation
import java.security.MessageDigest

/**
 * A verified invite redemption (D100/D102): [inviter] is the INVITE's author (the network's id16 when [fromNetwork]),
 * [createdMs] the INVITE's signed creation time (rights and revocation are judged at it), [rank] = SHA-256(invite_proof).
 */
class Redemption(val inviteId: ByteArray, val rank: ByteArray, val inviter: ByteArray, val fromNetwork: Boolean, val createdMs: Long)

/** A network-signed REVOCATION (D99). */
class RevocationRecord(val userId: ByteArray, val createdMs: Long)

/** A network-signed INVITE_RIGHTS (D101). */
class RightsRecord(val userId: ByteArray, val canInvite: Boolean, val version: Long, val createdMs: Long)

/** Membership rules the phone checks itself (D104): a node can hide entries, it cannot invent members. */
object Membership {
    /** An invite lives at most this long, created_ms to expires_ms, as on nodes (Go `records.MaxInviteTTL`, C8, D107). */
    const val MAX_INVITE_TTL_MS = 30L * 24 * 3_600_000

    /**
     * The redemption of [e] if its chain verifies like Go `admitChain` (non-strict): the INVITE is signed by the network
     * key or by the account key [keyOf] gives for an already accepted member, the proof is the redeem key's signature
     * over this account, the invite lives at most [MAX_INVITE_TTL_MS] (as on nodes), and the entry was registered within
     * the invite's lifetime. Whether the inviter was allowed to
     * invite is the caller's check (it needs the revocation and rights records).
     */
    fun chain(e: DirectoryEntry, networkPub: ByteArray, ctx: SigContext, keyOf: (ByteArray) -> ByteArray?): Redemption? = runCatching {
        if (e.inviteRecord.isEmpty || e.inviteProof.size() != 64) return null
        val rec = Record.parseFrom(e.inviteRecord)
        val body = RecordBody.parseFrom(rec.body)
        if (body.type != RecordType.INVITE || body.author.size() != 16) return null
        val inv = Invite.parseFrom(body.payload)
        if (inv.inviteId.size() != 32 || inv.redeemPub.size() != 32) return null
        val author = body.author.toByteArray()
        val fromNetwork = author.contentEquals(Ids.id16(networkPub))
        val signer = if (fromNetwork) networkPub else keyOf(author) ?: return null
        if (!RecordCodec.verify(rec, signer, ctx)) return null
        val msg = InviteKeys.redeemMessage(inv.inviteId.toByteArray(), e.userId.toByteArray(), e.accountPub.toByteArray())
        if (!Ed25519.verify(inv.redeemPub.toByteArray(), msg, e.inviteProof.toByteArray())) return null
        if (!inviteLifetimeOk(body.createdMs, inv.expiresMs)) return null
        if (e.registeredMs < body.createdMs || e.registeredMs > inv.expiresMs) return null
        Redemption(inv.inviteId.toByteArray(), MessageDigest.getInstance("SHA-256").digest(e.inviteProof.toByteArray()),
            author, fromNetwork, body.createdMs)
    }.getOrNull()

    /** Go `records.InviteLifetimeOK`: the invite expires after its creation and lives at most [MAX_INVITE_TTL_MS]. */
    fun inviteLifetimeOk(createdMs: Long, expiresMs: Long): Boolean =
        expiresMs > createdMs && expiresMs - createdMs <= MAX_INVITE_TTL_MS

    /** A REVOCATION signed by the network key, else null (a node or member cannot remove anyone). */
    fun revocation(rec: Record, networkPub: ByteArray, ctx: SigContext): RevocationRecord? = runCatching {
        val body = networkBody(rec, networkPub, ctx, RecordType.REVOCATION) ?: return null
        val user = Revocation.parseFrom(body.payload).userId.toByteArray().takeIf { it.size == 16 } ?: return null
        RevocationRecord(user, body.createdMs)
    }.getOrNull()

    /** An INVITE_RIGHTS signed by the network key, else null. */
    fun rights(rec: Record, networkPub: ByteArray, ctx: SigContext): RightsRecord? = runCatching {
        val body = networkBody(rec, networkPub, ctx, RecordType.INVITE_RIGHTS) ?: return null
        val r = InviteRights.parseFrom(body.payload)
        val user = r.userId.toByteArray().takeIf { it.size == 16 } ?: return null
        RightsRecord(user, r.canInvite, r.version, body.createdMs)
    }.getOrNull()

    /**
     * Invite rights as of [atMs] (Go `CanInviteAt`): the newest version created at or before [atMs], "no" winning a
     * tie; without one every member may invite (D101 as changed by the owner).
     */
    fun canInviteAt(records: List<RightsRecord>, atMs: Long): Boolean =
        records.filter { it.createdMs <= atMs }
            .maxWithOrNull(compareBy<RightsRecord> { it.version }.thenBy { if (it.canInvite) 0 else 1 })?.canInvite ?: true

    private fun networkBody(rec: Record, networkPub: ByteArray, ctx: SigContext, type: RecordType): RecordBody? {
        val body = RecordBody.parseFrom(rec.body)
        if (body.type != type || !body.author.toByteArray().contentEquals(Ids.id16(networkPub)) || !RecordCodec.verify(rec, networkPub, ctx))
            return null
        return body
    }

    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
