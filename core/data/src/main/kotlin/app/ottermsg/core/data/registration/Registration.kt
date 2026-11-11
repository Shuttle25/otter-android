package app.ottermsg.core.data.registration

import androidx.room.withTransaction
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.crypto.ManifestException
import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
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
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.InviteUsed
import app.ottermsg.proto.v1.PreKeys
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
        if (name.isEmpty() || name.length > 64) throw RegistrationError.BadDisplayName
        val bundle = try { InviteLink.decode(link) } catch (e: IllegalArgumentException) { throw RegistrationError.BadLink }
        val networkPub = bundle.networkPub.toByteArray()
        val manifest = try { ManifestVerifier.verify(bundle.manifest.toByteArray(), networkPub, 0) } catch (e: ManifestException) { throw RegistrationError.BadLink }
        if (bundle.secret.size() != 32) throw RegistrationError.BadLink

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

        val entry = DirectoryEntry.newBuilder().setUserId(bs(userId)).setUsername(user).setDisplayName(name)
            .setAccountPub(bs(account.publicKey)).setIdentityPub(bs(identity.publicKey.serialize()))
            .setIdentitySig(bs(IdentityKeys.bindAccount(identity, account.publicKey))).setVersion(1).setCanInvite(true).build()
        val prekeys = PreKeys.newBuilder().setDeviceId(1).setRegistrationId(registrationId)
            .setSignedPrekey(signed).setKyberPrekey(kyber).addAllOneTime(oneTime).build()
        val inviteId = MessageDigest.getInstance("SHA-256").digest(bundle.secret.toByteArray())
        val used = InviteUsed.newBuilder().setInviteId(bs(inviteId)).setUserId(bs(userId)).build()
        fun rec(t: RecordType, p: MessageLite) = RecordCodec.sign(RecordCodec.body(t, userId, p.toByteArray(), createdMs = now), account)
        val req = RegisterRequest.newBuilder().setInviteSecret(bundle.secret)
            .setDirectoryEntry(rec(RecordType.DIRECTORY_ENTRY, entry)).setPrekeys(rec(RecordType.PREKEYS, prekeys))
            .setInviteUsed(rec(RecordType.INVITE_USED, used)).build()

        try {
            connector.withNode(Endpoints.urls(manifest, allowPlaintext)) { it.register(req) }
        } catch (e: RpcException) {
            throw map(e)
        } catch (e: IOException) {
            throw RegistrationError.Network
        }

        // Persist only after the node accepted the registration.
        db.withTransaction {
            db.accountDao().insert(AccountEntity(userId, user, name, vault.wrap(seed), now))
            settings.setRegistrationId(registrationId)
            settings.setNextOneTimeId(nextId)
            settings.setNetworkPub(networkPub)
            db.manifestDao().insert(ManifestEntity(manifest.version, bundle.manifest.toByteArray(), receivedMs = now))
            mem.copyInto(RoomSignalKeyValue(db.signalKvDao()))
            if (!bundle.inviterUserId.isEmpty) db.contactDao().upsert(ContactEntity(
                bundle.inviterUserId.toByteArray(), "", bundle.inviterName, bundle.inviterAccountPub.toByteArray(),
                bundle.inviterIdentityPub.toByteArray(), verified = true, identityChanged = false, revoked = false, entryVersion = 0))
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
            else -> RegistrationError.Unknown(e.message.orEmpty())
        }
    }

    companion object {
        val USERNAME = Regex("^[a-z0-9_]{3,32}$")
        const val ONE_TIME_PER_NODE = 50
    }
}
