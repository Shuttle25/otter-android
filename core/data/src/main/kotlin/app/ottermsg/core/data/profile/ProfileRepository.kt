package app.ottermsg.core.data.profile

import androidx.room.withTransaction
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.DirectoryEntries
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Profile edits (D39): the display name is saved on the phone at once (pending flag) and published as a newer
 * version of our own DIRECTORY_ENTRY whenever a session is up.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val accounts: AccountRepository, private val db: OtterDatabase, private val settings: Settings, private val clock: Clock,
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val publishing = Mutex()
    private val _pending = MutableStateFlow(false)
    /** True while a name change waits for a node (the profile screen shows a note). */
    val pending: StateFlow<Boolean> = _pending

    /** Reads the persisted flag (call once at startup). */
    suspend fun refresh() { _pending.value = settings.profilePending() }

    /** Saves the display name on the phone at once; it reaches the directory when online. */
    suspend fun setDisplayName(raw: String): Result<Unit> {
        val name = raw.trim()
        DirectoryEntries.validate(name)?.let { return Result.failure(it) }
        val me = accounts.require()
        if (name == me.displayName) return Result.success(Unit)
        db.withTransaction {
            db.accountDao().setDisplayName(name)
            settings.setProfilePending(true)
        }
        accounts.reload()
        markPending()
        return Result.success(Unit)
    }

    /** «Показывать меня в каталоге сети» (D136): saved at once, published as a new version of our entry when online. */
    suspend fun setListed(v: Boolean) {
        if (v == settings.catalogListed() && !settings.profilePending()) return
        db.withTransaction {
            settings.setCatalogListed(v)
            settings.setProfilePending(true)
        }
        markPending()
    }

    private fun markPending() {
        _pending.value = true
        kicks.trySend(Unit)
    }

    /** Runs for one connected session: publishes a pending change now and after every later edit. */
    suspend fun run(api: NodeApi): Nothing {
        while (true) {
            try {
                publishIfPending(api)
                kicks.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.INFO, "profile publish failed; retrying", e)
                withTimeoutOrNull(RETRY_MS) { kicks.receive() }
            }
        }
    }

    suspend fun publishIfPending(api: NodeApi) = publishing.withLock {
        repeat(MAX_TRIES) {
            if (!settings.profilePending()) { _pending.value = false; return@withLock }
            val me = accounts.require()
            val version = settings.entryVersion() + 1
            val listed = settings.catalogListed()
            try {
                api.updateDirectory(DirectoryEntries.record(me, me.displayName, version, clock.now(), ownInvite(api, me.userId), listed))
                db.withTransaction {
                    settings.setEntryVersion(version)
                    // an edit made during the call keeps the flag: compare with what we sent
                    if (db.accountDao().get()?.displayName == me.displayName && settings.catalogListed() == listed)
                        settings.setProfilePending(false)
                }
                _pending.value = settings.profilePending()
                if (!_pending.value) return@withLock
            } catch (e: RpcException) {
                if (e.status != Status.STATUS_CONFLICT) throw e
                val mine = api.directoryList().mapNotNull { ownVersion(it, me.userId) }.maxOrNull() ?: throw e
                settings.setEntryVersion(maxOf(mine, settings.entryVersion()))
            }
        }
    }

    /**
     * Our invite chain for the new version (D100). A restored phone that has not synced the directory yet learns it
     * from its own entry first (signed by our key, so a node cannot change it); founding members have none.
     */
    private suspend fun ownInvite(api: NodeApi, me: ByteArray): Pair<ByteArray, ByteArray>? {
        settings.ownInvite()?.let { return it }
        val ctx = accounts.sigContext()
        val e = api.directoryList().mapNotNull { DirectorySync.verifyEntry(it, ctx) }
            .filter { it.userId.toByteArray().contentEquals(me) && !it.inviteRecord.isEmpty }
            .maxByOrNull { it.version } ?: return null
        settings.setOwnInvite(e.inviteRecord.toByteArray(), e.inviteProof.toByteArray())
        return settings.ownInvite()
    }

    private fun ownVersion(rec: Record, me: ByteArray): Int? = runCatching {
        val body = RecordBody.parseFrom(rec.body)
        if (body.type != RecordType.DIRECTORY_ENTRY || !body.author.toByteArray().contentEquals(me)) return null
        DirectoryEntry.parseFrom(body.payload).version.toInt()
    }.getOrNull()

    companion object {
        const val RETRY_MS = 30_000L
        const val MAX_TRIES = 3
        private val log: Logger = Logger.getLogger("ProfileRepository")
    }
}
