package app.ottermsg.core.data.directory

import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.dao.AccountDao
import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.dao.ConversationDao
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.signal.IdentityKeys
import app.ottermsg.proto.v1.DirectoryEntry
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Pulls the directory and upserts verified entries as contacts. */
@Singleton
class DirectorySync @Inject constructor(
    private val accounts: AccountRepository, private val contacts: ContactDao, private val conversations: ConversationDao,
    private val settings: Settings, private val accountDao: AccountDao,
) {
    private val mutex = Mutex()
    private val _synced = MutableStateFlow(false)
    /** True once a directory sync has completed in this process (the chat list's empty state waits for it). */
    val synced: StateFlow<Boolean> = _synced

    suspend fun sync(api: NodeApi) {
        apply(api.directory())
        _synced.value = true
    }

    suspend fun apply(records: List<Record>) = mutex.withLock {
        val me = accounts.require()
        for (rec in records) {
            val e = verifyEntry(rec) ?: continue
            val userId = e.userId.toByteArray()
            if (userId.contentEquals(me.userId)) { applyOwn(e); continue }
            val old = contacts.get(userId)
            if (old != null && old.entryVersion > e.version) continue // stale entry
            val identity = e.identityPub.toByteArray()
            val changed = old != null && !old.identityPub.contentEquals(identity)
            contacts.upsert(ContactEntity(
                userId = userId, username = e.username, displayName = e.displayName,
                accountPub = e.accountPub.toByteArray(), identityPub = identity,
                verified = (old?.verified ?: false) && !changed,
                identityChanged = (old?.identityChanged ?: false) || changed,
                revoked = false, entryVersion = e.version,
            ))
            if (old == null || old.displayName != e.displayName) conversations.setTitle(userId, e.displayName)
        }
    }

    /**
     * Our own entry: follow its version (profile updates continue from it); when no local edit is pending, adopt
     * its name (an edit made by another install of the account, or a restore).
     */
    private suspend fun applyOwn(e: DirectoryEntry) {
        if (e.version.toInt() > settings.entryVersion()) settings.setEntryVersion(e.version.toInt())
        if (!settings.profilePending() && e.version.toInt() >= settings.entryVersion() &&
            e.displayName != accounts.require().displayName) {
            accountDao.setDisplayName(e.displayName)
            accounts.reload()
        }
    }

    companion object {
        /** The entry if [rec] is a self-certifying, correctly bound DIRECTORY_ENTRY (sync and words restore, C18). */
        fun verifyEntry(rec: Record): DirectoryEntry? = runCatching {
            val body = RecordBody.parseFrom(rec.body)
            if (body.type != RecordType.DIRECTORY_ENTRY) return null
            val e = DirectoryEntry.parseFrom(body.payload)
            val accountPub = e.accountPub.toByteArray()
            val ok = accountPub.size == 32 &&
                body.author == e.userId &&
                e.userId.toByteArray().contentEquals(Ids.id16(accountPub)) &&
                RecordCodec.verify(rec, accountPub) &&
                IdentityKeys.verifyBinding(e.identityPub.toByteArray(), accountPub, e.identitySig.toByteArray())
            if (ok) e else null
        }.getOrNull()
    }
}
