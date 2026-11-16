package app.ottermsg.core.data.backup

import android.content.Context
import app.ottermsg.core.crypto.BackupKeys
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.SeedVault
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.data.media.BlobMetaBuilder
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.BackupMeta
import app.ottermsg.proto.v1.BlobMeta
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

sealed interface BackupState {
    data object Off : BackupState
    /** [lastMs] = 0: no successful backup yet. [error]: BackupManager.ERR_* of the last attempt, 0 = none. */
    data class On(val lastMs: Long, val running: Boolean, val error: Int) : BackupState
}

/** Password backups (D50/D55). Keys are derived once and kept wrapped; backups run while the app is in use. */
@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: OtterDatabase, private val accounts: AccountRepository, private val settings: Settings,
    private val vault: SeedVault, private val online: Provider<OnlineApi>, private val clock: Clock,
) {
    /** Key derivation; tests swap in cheap parameters. */
    internal var derive: (String, ByteArray, BackupKeys.Profile) -> BackupKeys.Keys = { pw, salt, p -> BackupKeys.derive(pw, salt, p) }
    private val mutex = Mutex()
    private val running = MutableStateFlow(false)

    val state: Flow<BackupState> = combine(settings.observeBackupEnabled(), settings.observeBackupLastMs(),
        settings.observeBackupError(), running) { on, last, err, run ->
        if (!on) BackupState.Off else BackupState.On(last, run, err)
    }

    /** Sets the password (first time or change: new salt, new keys) and backs up at once. */
    suspend fun setPassword(password: String): Int {
        if (PasswordPolicy.check(password) != PasswordPolicy.Result.OK) return ERR_WEAK
        val salt = BackupKeys.newSalt()
        val profile = BackupKeys.Profile.CURRENT
        val keys = withContext(Dispatchers.Default) { derive(password, salt, profile) }
        mutex.withLock {
            settings.setBackupSalt(salt)
            settings.setBackupProfile(profile)
            settings.setBackupSecret(vault.wrap(keys.backupKey + keys.authSecret))
            settings.setBackupError(0)
        }
        return runNow()
    }

    /** «Создать сейчас»: needs a connection. */
    suspend fun runNow(): Int {
        val api = online.get().current() ?: return ERR_OFFLINE.also { settings.setBackupError(it) }
        return run(api)
    }

    /** From each sync session: back up if the last success is older than 24 h; without a backup, make sure of the decoy. */
    suspend fun runIfDue(api: NodeApi) {
        if (settings.backupSecret() == null) { publishDecoyIfNeeded(api); return }
        if (clock.now() - settings.backupLastMs() < DAY_MS) return
        run(api)
    }

    /**
     * C20: an account without a backup publishes one decoy BACKUP_META (version 0, random salt and verifier, no blob),
     * so every node answers backup.auth for it exactly as for an account with a backup. A real backup replaces it.
     * A node older than 0.9.0 refuses it (INVALID): tried again next session.
     */
    internal suspend fun publishDecoyIfNeeded(api: NodeApi) {
        if (settings.backupDecoyDone() || settings.backupVersion() != 0L) return
        val me = accounts.require()
        val profile = BackupKeys.Profile.CURRENT
        val meta = BackupMeta.newBuilder().setVersion(0).setSalt(bs(BackupKeys.newSalt()))
            .setKdfMKib(profile.mKib).setKdfT(profile.t).setKdfP(profile.p)
            .setAuthVerifier(bs(ByteArray(32).also(SecureRandom()::nextBytes)))
            .setBlobKey(bs(me.userId + ByteArray(16))).setSize(0).build()
        try {
            api.backupPublish(me.sign(RecordCodec.body(RecordType.BACKUP_META, me.userId, meta.toByteArray(),
                createdMs = clock.now())))
            settings.setBackupDecoyDone(true)
        } catch (e: RpcException) {
            // refused (an older node): next session
        } catch (e: IOException) {
            // connection dropped: next session
        }
    }

    private suspend fun run(api: NodeApi): Int = mutex.withLock {
        val wrapped = settings.backupSecret() ?: return ERR_OFF
        running.value = true
        val file = File(context.cacheDir, "backup-${System.nanoTime()}.otb")
        try {
            val secret = vault.unwrap(wrapped)
            val backupKey = secret.copyOfRange(0, 32); val authSecret = secret.copyOfRange(32, 64)
            val me = accounts.require()
            val id = ByteArray(16).also(SecureRandom()::nextBytes) // fresh per run: nodes collect a failed run's blob (D50)
            val blobKey = BlobMetaBuilder.blobKey(me.userId, id)
            withContext(Dispatchers.IO) {
                val gz = SnapshotCodec.gzip(SnapshotCodec.export(db, me, settings, clock.now()))
                BlobCipher.encrypt(gz.inputStream(), file, backupKey, blobKey)
            }
            val hashes = withContext(Dispatchers.IO) { BlobCipher.chunkHashes(file) }
            val meta = BlobMeta.newBuilder().setSize(file.length()).setChunkSize(BlobCipher.CHUNK)
                .addAllChunkSha256(hashes.map(::bs)).setPersistent(true).build()
            val metaRec = me.sign(RecordCodec.body(RecordType.BLOB_META, me.userId, meta.toByteArray(), id = id,
                createdMs = clock.now()))
            val status = api.blobCreate(metaRec)
            val missing = status.missingList.toSet()
            file.inputStream().buffered().use { s ->
                val buf = ByteArray(BlobCipher.CHUNK)
                for (i in 0 until status.chunks) {
                    val n = BlobCipher.readFully(s, buf)
                    if (i in missing) api.blobPut(blobKey, i, buf.copyOf(n))
                }
            }
            val version = settings.backupVersion() + 1
            val profile = settings.backupProfile()
            val bm = BackupMeta.newBuilder().setVersion(version).setSalt(bs(checkNotNull(settings.backupSalt())))
                .setKdfMKib(profile.mKib).setKdfT(profile.t).setKdfP(profile.p)
                .setAuthVerifier(bs(BackupKeys.Keys(backupKey, authSecret).verifier)).setBlobKey(bs(blobKey)).setSize(file.length()).build()
            api.backupPublish(me.sign(RecordCodec.body(RecordType.BACKUP_META, me.userId, bm.toByteArray(), createdMs = clock.now())))
            settings.setBackupVersion(version)
            settings.setBackupLastMs(clock.now())
            settings.setBackupError(0)
            OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            val code = if (e.status == Status.STATUS_QUOTA) ERR_QUOTA else ERR_NETWORK
            settings.setBackupError(code); code
        } catch (e: IOException) {
            settings.setBackupError(ERR_NETWORK); ERR_NETWORK
        } finally {
            file.delete()
            running.value = false
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val OK = 0; const val ERR_WEAK = 1; const val ERR_OFFLINE = 2; const val ERR_NETWORK = 3; const val ERR_QUOTA = 4; const val ERR_OFF = 5
    }
}
