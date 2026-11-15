package app.ottermsg.core.data.registration

import androidx.room.withTransaction
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteKeys
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.crypto.ManifestException
import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.DirectoryEntries
import app.ottermsg.core.data.account.SeedVault
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.di.AllowPlaintext
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.signal.RoomSignalKeyValue
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AccountEntity
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ManifestEntity
import app.ottermsg.core.network.Endpoints
import app.ottermsg.core.network.RpcException
import app.ottermsg.core.signal.IdentityKeys
import app.ottermsg.core.signal.InMemorySignalKeyValue
import app.ottermsg.core.signal.OtterProtocolStore
import app.ottermsg.core.signal.PreKeyFactory
import app.ottermsg.proto.v1.Invite
import app.ottermsg.proto.v1.InviteUsed
import app.ottermsg.proto.v1.PreKeys
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.RegisterRequest
import app.ottermsg.proto.v1.Status
import com.google.protobuf.MessageLite
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject

sealed class RegistrationError : Exception() {
    data object BadUsername : RegistrationError() { private fun readResolve(): Any = BadUsername }
    data object BadDisplayName : RegistrationError() { private fun readResolve(): Any = BadDisplayName }
    data object UsernameTaken : RegistrationError() { private fun readResolve(): Any = UsernameTaken }
    data object InviteUsed : RegistrationError() { private fun readResolve(): Any = InviteUsed }
    data object InviteExpired : RegistrationError() { private fun readResolve(): Any = InviteExpired }
    /** A link made by 0.9.0 or older (no signed INVITE): ask for a new one (D100). */
    data object InviteOutdated : RegistrationError() { private fun readResolve(): Any = InviteOutdated }
    data object InviteUnknown : RegistrationError() { private fun readResolve(): Any = InviteUnknown }
    data object BadLink : RegistrationError() { private fun readResolve(): Any = BadLink }
    data object Network : RegistrationError() { private fun readResolve(): Any = Network }
    data class Unknown(val detail: String) : RegistrationError()
}

/** Seam for the onboarding ViewModel tests. */
interface Registrar {
    suspend fun register(link: String, displayName: String, username: String, seed: ByteArray): Result<Unit>
}

class Registration @Inject constructor(
    private val db: OtterDatabase, private val connector: NodeConnector, private val vault: SeedVault,
    private val accounts: AccountRepository, private val settings: Settings, private val clock: Clock,
    @param:AllowPlaintext private val allowPlaintext: Boolean,
) : Registrar {

    override suspend fun register(link: String, displayName: String, username: String, seed: ByteArray): Result<Unit> = try {
        doRegister(link, displayName, username, seed)
        Result.success(Unit)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: RegistrationError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(RegistrationError.Unknown(e.message ?: e.javaClass.simpleName))
    }

    private suspend fun doRegister(link: String, displayName: String, username: String, seed: ByteArray) {
        val name = displayName.trim(); val user = username.trim().lowercase()
        if (!USERNAME.matches(user)) throw RegistrationError.BadUsername
        if (DirectoryEntries.validate(name) != null) throw RegistrationError.BadDisplayName
        val bundle = try { InviteLink.decode(link) } catch (e: IllegalArgumentException) { throw RegistrationError.BadLink }
        val networkPub = bundle.networkPub.toByteArray()
        val manifest = try { ManifestVerifier.verify(bundle.manifest.toByteArray(), networkPub, 0) } catch (e: ManifestException) { throw RegistrationError.BadLink }
        if (bundle.secret.size() != 32) throw RegistrationError.BadLink
        // D100: a link made before 0.9.1 has no signed INVITE; no node admits it any more.
        if (!bundle.hasInvite()) throw RegistrationError.InviteOutdated
        val secret = bundle.secret.toByteArray()
        val inviteId = MessageDigest.getInstance("SHA-256").digest(secret)
        val redeem = InviteKeys.redeemKey(secret)
        if (!inviteMatches(bundle.invite, inviteId, redeem.publicKey)) throw RegistrationError.BadLink
        val inviteRecord = bundle.invite.toByteArray()

        val account = SeedKeys.accountKeyPair(seed)
        val userId = Ids.id16(account.publicKey)
        val identity = IdentityKeys.fromSeed(seed)
        val registrationId = SecureRandom().nextInt(16380) + 1
        val mem = InMemorySignalKeyValue()
        val store = OtterProtocolStore(identity, registrationId, mem)
        val now = clock.now()
        val signed = PreKeyFactory.signed(store, 1, now)
        val kyber = PreKeyFactory.kyberLastResort(store, 1, now)
        var nextId = 1
        val oneTime = manifest.nodesList.flatMap { n ->
            PreKeyFactory.oneTime(store, nextId, ONE_TIME_PER_NODE, n.nodeId.toByteArray()).also { nextId += ONE_TIME_PER_NODE }
        }

        // The proof replaces the secret: the secret never leaves this phone (D100).
        val proof = redeem.sign(InviteKeys.redeemMessage(inviteId, userId, account.publicKey))
        val entry = DirectoryEntries.build(userId, user, name, seed, version = 1, registeredMs = now, invite = inviteRecord to proof)
        val prekeys = PreKeys.newBuilder().setDeviceId(1).setRegistrationId(registrationId).setInstalledMs(now)
            .setSignedPrekey(signed).setKyberPrekey(kyber).addAllOneTime(oneTime).build()
        val used = InviteUsed.newBuilder().setInviteId(bs(inviteId)).setUserId(bs(userId)).build()
        fun rec(t: RecordType, p: MessageLite) = RecordCodec.sign(RecordCodec.body(t, userId, p.toByteArray(), createdMs = now), account)
        val req = RegisterRequest.newBuilder() // no invite_secret (D100)
            .setDirectoryEntry(rec(RecordType.DIRECTORY_ENTRY, entry)).setPrekeys(rec(RecordType.PREKEYS, prekeys))
            .setInviteUsed(rec(RecordType.INVITE_USED, used)).build()

        try {
            connector.withNode(Endpoints.candidates(manifest, allowPlaintext)) { it.register(req) }
        } catch (e: RpcException) {
            throw map(e)
        } catch (e: IOException) {
            throw RegistrationError.Network
        }

        // Persist only after the node accepted the registration.
        db.withTransaction {
            db.accountDao().insert(AccountEntity(userId, user, name, vault.wrap(seed), now))
            settings.setRegistrationId(registrationId)
            settings.setInstalledMs(now)
            settings.setNextOneTimeId(nextId)
            settings.setNetworkPub(networkPub)
            settings.setOwnInvite(inviteRecord, proof)
            db.manifestDao().insert(ManifestEntity(manifest.version, bundle.manifest.toByteArray(), receivedMs = now))
            mem.copyInto(RoomSignalKeyValue(db.signalKvDao()))
            // C23: the link's inviter fields are not signed. Keep them only as a placeholder (name in the chat list):
            // entryVersion 0 = "only from a link", never verified; the directory entry replaces it.
            val inviterPub = bundle.inviterAccountPub.toByteArray()
            if (!bundle.inviterUserId.isEmpty && inviterPub.size == 32 &&
                bundle.inviterUserId.toByteArray().contentEquals(Ids.id16(inviterPub))) db.contactDao().upsert(ContactEntity(
                bundle.inviterUserId.toByteArray(), "", bundle.inviterName, inviterPub,
                bundle.inviterIdentityPub.toByteArray(), verified = false, identityChanged = false, revoked = false, entryVersion = 0))
        }
        accounts.load()
    }

    private fun map(e: RpcException): RegistrationError {
        val msg = e.message.orEmpty().lowercase()
        return when {
            e.status == Status.STATUS_CONFLICT && "username" in msg -> RegistrationError.UsernameTaken
            e.status == Status.STATUS_CONFLICT && "used" in msg -> RegistrationError.InviteUsed
            e.status == Status.STATUS_CONFLICT && "expired" in msg -> RegistrationError.InviteExpired
            e.status == Status.STATUS_NOT_FOUND -> RegistrationError.InviteUnknown
            e.status == Status.STATUS_INVALID && "invite chain" in msg -> RegistrationError.InviteOutdated
            else -> RegistrationError.Unknown(e.message.orEmpty())
        }
    }

    /** The link's INVITE belongs to its secret (else no proof could verify: a tampered or swapped link). */
    private fun inviteMatches(rec: Record, inviteId: ByteArray, redeemPub: ByteArray): Boolean = runCatching {
        val body = RecordBody.parseFrom(rec.body)
        val inv = Invite.parseFrom(body.payload)
        body.type == RecordType.INVITE && inv.inviteId.toByteArray().contentEquals(inviteId) &&
            inv.redeemPub.toByteArray().contentEquals(redeemPub)
    }.getOrDefault(false)

    companion object {
        val USERNAME = Regex("^[a-z0-9_]{3,32}$")
        const val ONE_TIME_PER_NODE = 50
    }
}
