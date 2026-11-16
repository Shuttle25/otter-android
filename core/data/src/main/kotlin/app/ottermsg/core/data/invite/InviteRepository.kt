package app.ottermsg.core.data.invite

import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.dao.ManifestDao
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.InviteBundle
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import com.google.protobuf.ByteString
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days

/** The API of the current online session, if any (implemented by SyncEngine). */
fun interface OnlineApi { fun current(): NodeApi? }

class NotConnectedException : IOException("not connected")

/** invite.create → UNAUTHORIZED: the administrator withdrew our invite rights (D101). */
class NoInviteRightsException : Exception("no invite rights")

data class CreatedInvite(val link: String, val expiresMs: Long)

@Singleton
class InviteRepository @Inject constructor(
    private val accounts: AccountRepository, private val settings: Settings, private val manifests: ManifestDao,
    private val online: OnlineApi, private val clock: Clock,
) {
    /** Publishes a single-use invite valid for 7 days and returns its link. */
    suspend fun create(): CreatedInvite {
        val api = online.current() ?: throw NotConnectedException()
        val me = accounts.require()
        val manifest = checkNotNull(manifests.latest()) { "no manifest" }
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val now = clock.now()
        val expires = now + TTL_MS
        // D100: the redeem key's public half is in the signed record; the invitee proves the secret with it.
        val inv = Invite.newBuilder().setInviteId(bs(MessageDigest.getInstance("SHA-256").digest(secret))).setExpiresMs(expires)
            .setRedeemPub(bs(InviteKeys.redeemKey(secret).publicKey)).build()
        val rec = me.sign(RecordCodec.body(RecordType.INVITE, me.userId, inv.toByteArray(), expiresMs = expires, createdMs = now))
        try {
            api.createInvite(rec)
        } catch (e: RpcException) {
            if (e.status == Status.STATUS_UNAUTHORIZED) throw NoInviteRightsException()
            throw e
        }
        val bundle = InviteBundle.newBuilder()
            .setNetworkPub(bs(settings.networkPub()))
            .setManifest(app.ottermsg.proto.v1.Record.parseFrom(manifest.raw))
            .setSecret(ByteString.copyFrom(secret))
            .setInviterUserId(bs(me.userId)).setInviterAccountPub(bs(me.accountKey.publicKey))
            .setInviterIdentityPub(bs(me.identity.publicKey.serialize())).setInviterName(me.displayName)
            .setInvite(rec)
            .build()
        return CreatedInvite(InviteLink.encode(bundle), expires)
    }

    companion object { val TTL_MS = 7.days.inWholeMilliseconds }
}
