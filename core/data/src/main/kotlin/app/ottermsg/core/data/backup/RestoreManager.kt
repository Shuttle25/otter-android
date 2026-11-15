package app.ottermsg.core.data.backup

import androidx.room.withTransaction
import app.ottermsg.core.crypto.BackupKeys
import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.SeedVault
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.di.AllowPlaintext
import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.AccountEntity
import app.ottermsg.core.database.entity.ManifestEntity
import app.ottermsg.core.network.Endpoints
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.BackupMeta
import app.ottermsg.proto.v1.BackupSnapshot
import app.ottermsg.proto.v1.BlobMeta
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

sealed class RestoreError : Exception() {
    data object BadLink : RestoreError() { private fun readResolve(): Any = BadLink }
    data object BadWords : RestoreError() { private fun readResolve(): Any = BadWords }
    data object WrongPassword : RestoreError() { private fun readResolve(): Any = WrongPassword }
    data object TooManyAttempts : RestoreError() { private fun readResolve(): Any = TooManyAttempts }
    data object NotReplicated : RestoreError() { private fun readResolve(): Any = NotReplicated }
    data object UnknownAccount : RestoreError() { private fun readResolve(): Any = UnknownAccount }
    data object Corrupt : RestoreError() { private fun readResolve(): Any = Corrupt }
    data object Network : RestoreError() { private fun readResolve(): Any = Network }
}

/** Seam for the onboarding ViewModel tests. */
interface Restorer {
    suspend fun withPassword(link: String, username: String, password: String): Result<Unit>
    suspend fun withWords(link: String, words: List<String>): Result<Unit>
    /** After a words restore: add the history from the backup (same password check as a full restore). */
    suspend fun addHistory(password: String): Result<Unit>
}

/**
 * Restores an account on a fresh install (D54/D55). The network comes from any family invite link (only its signed
 * manifest is used; the invite is not consumed). Sessions are re-established after the first login (InstallGuard).
 */
@Singleton
class RestoreManager @Inject constructor(
    private val db: OtterDatabase, private val connector: NodeConnector, private val vault: SeedVault,
    private val accounts: AccountRepository, private val settings: Settings, private val clock: Clock,
    @param:AllowPlaintext private val allowPlaintext: Boolean,
) : Restorer {
    /** Key derivation; tests swap in cheap parameters (the profile's numbers are ignored then). */
    internal var derive: (String, ByteArray, BackupKeys.Profile) -> BackupKeys.Keys = { pw, salt, p -> BackupKeys.derive(pw, salt, p) }

    private class Network(val manifest: Manifest, val raw: ByteArray, val networkPub: ByteArray)

    private fun network(link: String): Network {
        val b = try { InviteLink.decode(link) } catch (e: IllegalArgumentException) { throw RestoreError.BadLink }
        val pub = b.networkPub.toByteArray()
        val m = try { ManifestVerifier.verify(b.manifest.toByteArray(), pub, 0) } catch (e: Exception) { throw RestoreError.BadLink }
        return Network(m, b.manifest.toByteArray(), pub)
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: RestoreError) { Result.failure(e) }
        catch (e: RpcException) { Result.failure(map(e)) }
        catch (e: IOException) { Result.failure(RestoreError.Network) }

    private fun map(e: RpcException): RestoreError = when {
        e.status == Status.STATUS_UNAUTHORIZED -> RestoreError.WrongPassword
        e.status == Status.STATUS_RETRY_LATER && "too many" in e.message.orEmpty() -> RestoreError.TooManyAttempts
        e.status == Status.STATUS_RETRY_LATER -> RestoreError.NotReplicated
        else -> RestoreError.Network
    }

    override suspend fun withPassword(link: String, username: String, password: String): Result<Unit> = guarded {
        check(accounts.account.value == null) { "an account is already installed" }
        val net = network(link)
        val d = connector.withNode(Endpoints.candidates(net.manifest, allowPlaintext)) { api ->
            download(api, username.trim().lowercase(), password)
        }
        install(net, d.snapshot, d)
    }

    private class Downloaded(val snapshot: BackupSnapshot, val keys: BackupKeys.Keys, val meta: BackupMeta, val profile: BackupKeys.Profile)

    /** Fetches, verifies and decrypts the backup over one connection (the fetch grant is per connection, D51). */
    private suspend fun download(api: NodeApi, username: String, password: String): Downloaded {
        val auth = api.backupAuth(username)
        // C1: the node's numbers only select one of our profiles; anything else ends here, before the password is used
        val profile = BackupKeys.Profile.match(auth.kdfMKib, auth.kdfT, auth.kdfP, auth.salt.size()) ?: throw RestoreError.Corrupt
        val keys = withContext(Dispatchers.Default) { derive(password, auth.salt.toByteArray(), profile) }
        val res = api.backupFetch(username, keys.authSecret)
        val metaBody = RecordBody.parseFrom(res.backupMeta.body)
        val meta = BackupMeta.parseFrom(metaBody.payload)
        val blob = BlobMeta.parseFrom(RecordBody.parseFrom(res.blobMeta.body).payload)
        val tmp = File.createTempFile("restore", ".otb")
        try {
            tmp.outputStream().buffered().use { out ->
                for (i in 0 until blob.chunkSha256Count) {
                    val data = api.blobGet(meta.blobKey.toByteArray(), i)
                    if (!MessageDigest.getInstance("SHA-256").digest(data).contentEquals(blob.getChunkSha256(i).toByteArray())) throw RestoreError.Corrupt
                    out.write(data)
                }
            }
            val snapshot = withContext(Dispatchers.IO) {
                try {
                    BlobCipher.decrypt(tmp, keys.backupKey, meta.blobKey.toByteArray()).use { SnapshotCodec.gunzip(it) }
                } catch (e: IOException) { throw RestoreError.Corrupt } catch (e: GeneralSecurityException) { throw RestoreError.Corrupt }
            }
            // the snapshot must belong to the account that signed the BACKUP_META
            val author = Ids.id16(SeedKeys.accountKeyPair(snapshot.seed.toByteArray()).publicKey)
            if (snapshot.format != SnapshotCodec.FORMAT || !author.contentEquals(metaBody.author.toByteArray())) throw RestoreError.Corrupt
            return Downloaded(snapshot, keys, meta, profile)
        } finally { tmp.delete() }
    }

    override suspend fun withWords(link: String, words: List<String>): Result<Unit> = guarded {
        check(accounts.account.value == null) { "an account is already installed" }
        val net = network(link)
        val seed = try { Bip39.toEntropy(words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }) }
            catch (e: IllegalArgumentException) { throw RestoreError.BadWords }
        val account = SeedKeys.accountKeyPair(seed)
        val userId = Ids.id16(account.publicKey)
        val entry = connector.withNode(Endpoints.candidates(net.manifest, allowPlaintext)) { api ->
            try { api.login(userId, account) } catch (e: RpcException) {
                if (e.status == Status.STATUS_UNAUTHORIZED || e.status == Status.STATUS_RETRY_LATER) throw RestoreError.UnknownAccount
                throw e
            }
            api.directory().map { RecordBody.parseFrom(it.body) }.filter { it.type == RecordType.DIRECTORY_ENTRY }
                .map { DirectoryEntry.parseFrom(it.payload) }.firstOrNull { it.userId.toByteArray().contentEquals(userId) }
        } ?: throw RestoreError.UnknownAccount
        val snapshot = BackupSnapshot.newBuilder().setFormat(SnapshotCodec.FORMAT).setSeed(bs(seed)).setUsername(entry.username)
            .setDisplayName(entry.displayName).setRegisteredMs(entry.registeredMs).setEntryVersion(entry.version.toInt())
            .setPhotoAutoDownload(Settings.AUTO_WIFI).build()
        install(net, snapshot, null)
    }

    override suspend fun addHistory(password: String): Result<Unit> = guarded {
        val me = accounts.require()
        val m = ManifestVerifier.verify(checkNotNull(db.manifestDao().latest()) { "no manifest" }.raw, settings.networkPub(), 0)
        val d = connector.withNode(Endpoints.candidates(m, allowPlaintext)) { api -> download(api, me.username, password) }
        if (!d.snapshot.seed.toByteArray().contentEquals(me.seed)) throw RestoreError.Corrupt
        SnapshotCodec.import(d.snapshot, db, settings)
        keepBackupOn(d)
    }

    /** The restored phone keeps backing up with the same password; versions continue from the restored one. */
    private suspend fun keepBackupOn(d: Downloaded) {
        settings.setBackupSalt(d.meta.salt.toByteArray())
        settings.setBackupProfile(d.profile)
        settings.setBackupSecret(vault.wrap(d.keys.backupKey + d.keys.authSecret))
        settings.setBackupVersion(d.meta.version)
        settings.setBackupLastMs(0) // back up from this phone at the first session
    }

    private suspend fun install(net: Network, s: BackupSnapshot, d: Downloaded?) {
        val seed = s.seed.toByteArray()
        val userId = Ids.id16(SeedKeys.accountKeyPair(seed).publicKey)
        val now = clock.now()
        db.withTransaction {
            db.accountDao().insert(AccountEntity(userId, s.username, s.displayName, vault.wrap(seed), s.registeredMs))
            settings.setRegistrationId(SecureRandom().nextInt(16380) + 1) // a new install (D52)
            settings.setInstalledMs(now)
            settings.setRestorePending(true)
            settings.setNetworkPub(net.networkPub)
            db.manifestDao().insert(ManifestEntity(net.manifest.version, net.raw, receivedMs = now))
            SnapshotCodec.import(s, db, settings)
            if (d != null) keepBackupOn(d)
        }
        accounts.load()
    }
}
