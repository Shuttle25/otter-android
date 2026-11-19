package app.ottermsg.core.data.account

import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.bs
import app.ottermsg.core.signal.IdentityKeys
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType

/** Builds our own DIRECTORY_ENTRY record (registration and profile updates use the same fields). */
object DirectoryEntries {
    /** [invite] = our chain (INVITE record bytes, redeem proof), D100; null for founding members (no chain). */
    fun build(userId: ByteArray, username: String, displayName: String, seed: ByteArray, version: Int, registeredMs: Long,
              invite: Pair<ByteArray, ByteArray>? = null, listed: Boolean = false): DirectoryEntry {
        val account = SeedKeys.accountKeyPair(seed)
        val identity = IdentityKeys.fromSeed(seed)
        // can_invite is no longer set: nodes ignore it (D101)
        val b = DirectoryEntry.newBuilder().setUserId(bs(userId)).setUsername(username).setDisplayName(displayName)
            .setAccountPub(bs(account.publicKey)).setIdentityPub(bs(identity.publicKey.serialize()))
            .setIdentitySig(bs(IdentityKeys.bindAccount(identity, account.publicKey)))
            .setVersion(version.toLong()).setRegisteredMs(registeredMs).setListed(listed)
        invite?.let { (rec, proof) -> b.setInviteRecord(bs(rec)).setInviteProof(bs(proof)) }
        return b.build()
    }

    /** [listed] = «Показывать меня в каталоге сети» (D136); every new version carries the current value. */
    suspend fun record(me: LocalAccount, displayName: String, version: Int, nowMs: Long, invite: Pair<ByteArray, ByteArray>? = null,
                       listed: Boolean = false): Record =
        me.sign(RecordCodec.body(RecordType.DIRECTORY_ENTRY, me.userId,
            build(me.userId, me.username, displayName, me.seed, version, me.createdMs, invite, listed).toByteArray(), createdMs = nowMs))

    /** Display names: trimmed, 1–64 Unicode code points (the node counts runes). Null when valid. */
    fun validate(name: String): ProfileError? = when {
        name.isEmpty() -> ProfileError.Empty
        name.codePointCount(0, name.length) > 64 -> ProfileError.TooLong
        else -> null
    }
}

sealed class ProfileError : Exception() {
    data object Empty : ProfileError() { private fun readResolve(): Any = Empty }
    data object TooLong : ProfileError() { private fun readResolve(): Any = TooLong }
}
