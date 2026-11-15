package app.ottermsg.core.data.directory

import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.dao.AccountDao
import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.dao.ConversationDao
import app.ottermsg.core.database.dao.ManifestDao
import app.ottermsg.core.database.dao.MessageDao
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
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
import java.security.SecureRandom
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** Pulls the directory and upserts verified entries as contacts. */
@Singleton
class DirectorySync @Inject constructor(
    private val accounts: AccountRepository, private val contacts: ContactDao, private val conversations: ConversationDao,
    private val settings: Settings, private val accountDao: AccountDao, private val manifests: ManifestDao,
    private val messages: MessageDao, private val clock: Clock,
) {
    private val rng = SecureRandom()
    private val mutex = Mutex()
    private val _synced = MutableStateFlow(false)
    /** True once a directory sync has completed in this process (the chat list's empty state waits for it). */
    val synced: StateFlow<Boolean> = _synced

    suspend fun sync(api: NodeApi) {
        apply(api.directory())
        _synced.value = true
    }

    /**
     * Applies a directory batch (D104): a node can hide entries, it cannot invent members. An entry becomes a *new*
     * contact only if it is a founding member (signed manifest) or its invite chain verifies against the network key
     * or an already accepted member who could invite at the invite's time; network-signed REVOCATIONs mark contacts
     * revoked (sticky); of several redemptions of one invite the lowest SHA-256(invite_proof) stays (D102).
     */
    suspend fun apply(records: List<Record>) = mutex.withLock {
        val me = accounts.require()
        val networkPub = settings.networkPub()
        val founding = manifests.latest()?.let { runCatching { ManifestVerifier.verify(it.raw, networkPub, 0) }.getOrNull() }
            ?.foundingMembersList?.map { it.toByteArray().hex() }?.toSet().orEmpty()
        val revokedAt = HashMap<String, Long>() // earliest network-signed revocation per user
        val rights = HashMap<String, MutableList<RightsRecord>>()
        for (rec in records) {
            Membership.revocation(rec, networkPub)?.let { r -> revokedAt.merge(r.userId.hex(), r.createdMs, ::minOf) }
            Membership.rights(rec, networkPub)?.let { r -> rights.getOrPut(r.userId.hex()) { mutableListOf() } += r }
        }
        val known = contacts.all().associateBy { it.userId.hex() }
        val entries = records.mapNotNull { verifyEntry(it) }
        // Losers of a doubly redeemed invite never count as inviters; finding them can drop entries they invited, which
        // can change other groups, so repeat until the set is stable (it only grows, so this ends).
        val losers = HashMap<String, Redemption>()
        var accepted: List<Pair<DirectoryEntry, Redemption?>>
        while (true) {
            accepted = admit(entries, me, networkPub, founding, known, revokedAt, rights, losers.keys)
            val fresh = accepted.mapNotNull { (e, r) -> r?.let { e to it } }.groupBy { it.second.inviteId.hex() }.values
                .filter { it.size > 1 }
                .flatMap { g ->
                    g.sortedWith { a, b ->
                        Membership.compareUnsigned(a.second.rank, b.second.rank).takeIf { it != 0 }
                            ?: Membership.compareUnsigned(a.first.userId.toByteArray(), b.first.userId.toByteArray())
                    }.drop(1)
                }
                .filter { (e, _) -> e.userId.toByteArray().hex() !in losers }
            if (fresh.isEmpty()) break
            fresh.forEach { (e, r) -> losers[e.userId.toByteArray().hex()] = r }
        }
        val ignored = entries.size - accepted.size
        if (ignored > 0) log.info("$ignored directory entr(ies) without a verifiable invite ignored")
        for ((e, _) in accepted) {
            val userId = e.userId.toByteArray()
            if (userId.contentEquals(me.userId)) { applyOwn(e); continue }
            val old = contacts.get(userId)
            if (old != null && old.entryVersion > e.version) continue // stale entry
            val identity = e.identityPub.toByteArray()
            val changed = old != null && !old.identityPub.contentEquals(identity)
            val dup = losers[userId.hex()]
            val revoked = (old?.revoked ?: false) || userId.hex() in revokedAt || dup != null
            contacts.upsert(ContactEntity(
                userId = userId, username = e.username, displayName = e.displayName,
                accountPub = e.accountPub.toByteArray(), identityPub = identity,
                verified = (old?.verified ?: false) && !changed,
                identityChanged = (old?.identityChanged ?: false) || changed,
                revoked = revoked, entryVersion = e.version, resetMs = old?.resetMs ?: 0,
            ))
            if (old == null || old.displayName != e.displayName) conversations.setTitle(userId, e.displayName)
            if (revoked && old?.revoked != true) {
                // the inviter of a doubly used link learns which account was disabled; a removal shows in an open chat
                if (dup != null) { if (dup.inviter.contentEquals(me.userId)) memberLine(userId, e.displayName, DUPLICATE, create = true) }
                else memberLine(userId, e.displayName, REVOKED, create = false)
            }
        }
        // a revocation of a contact whose entry is not in this batch
        for (c in contacts.all()) {
            if (c.revoked || c.userId.hex() !in revokedAt) continue
            contacts.upsert(c.copy(revoked = true))
            memberLine(c.userId, c.displayName, REVOKED, create = false)
        }
    }

    /**
     * The entries this phone accepts: known contacts and ourselves, founding members, and entries whose invite chain
     * verifies (fixpoint over the batch: an inviter may come later in it).
     */
    private fun admit(
        entries: List<DirectoryEntry>, me: LocalAccount, networkPub: ByteArray, founding: Set<String>,
        known: Map<String, ContactEntity>, revokedAt: Map<String, Long>, rights: Map<String, List<RightsRecord>>,
        losers: Set<String>,
    ): List<Pair<DirectoryEntry, Redemption?>> {
        val keys = HashMap<String, ByteArray>()
        keys[me.userId.hex()] = me.accountKey.publicKey
        known.forEach { (hex, c) -> keys[hex] = c.accountPub }
        /** Whether the inviter of [r] could invite when the invite was signed (Go admitChain, D99/D101). */
        fun inviterAllowed(r: Redemption): Boolean {
            if (r.fromNetwork) return true
            val a = r.inviter.hex()
            if (a in losers) return false // disabled as a duplicate: its invites never admit anyone
            val at = revokedAt[a]
            if (at != null && at <= r.createdMs) return false
            // revoked here but no dated revocation in this batch (a duplicate loser, or a hidden record): not an inviter
            if (at == null && known[a]?.revoked == true) return false
            return Membership.canInviteAt(rights[a].orEmpty(), r.createdMs)
        }
        val pending = entries.toMutableList()
        val accepted = mutableListOf<Pair<DirectoryEntry, Redemption?>>()
        var progress = true
        while (progress) {
            progress = false
            val it = pending.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val id = e.userId.toByteArray().hex()
                val red = Membership.chain(e, networkPub) { a -> keys[a.hex()] }?.takeIf(::inviterAllowed)
                if (red != null || keys.containsKey(id) || id in founding) {
                    accepted += e to red
                    keys[id] = e.accountPub.toByteArray()
                    it.remove(); progress = true
                }
            }
        }
        return accepted
    }

    /** A SYSTEM_MEMBER line in the 1:1 chat; [create] opens the chat if there is none yet (the inviter's duplicate notice). */
    private suspend fun memberLine(userId: ByteArray, title: String, reason: String, create: Boolean) {
        val now = clock.now()
        val cid = if (create) conversations.getOrCreateDirect(userId, title, now) else conversations.byPeer(userId)?.id ?: return
        messages.insert(MessageEntity(messageId = ByteArray(16).also(rng::nextBytes), conversationId = cid, senderId = userId,
            outgoing = false, sentMs = now, receivedMs = now, body = "", status = MessageStatus.DELIVERED,
            kind = MessageKind.SYSTEM_MEMBER, systemData = reason.toByteArray(), readReceiptSent = true))
        conversations.touch(cid, now)
    }

    /**
     * Our own entry: follow its version (profile updates continue from it); when no local edit is pending, adopt
     * its name (an edit made by another install of the account, or a restore).
     */
    private suspend fun applyOwn(e: DirectoryEntry) {
        // a restored phone learns its invite chain from its own entry (signed by our key), D100
        if (settings.ownInvite() == null && !e.inviteRecord.isEmpty)
            settings.setOwnInvite(e.inviteRecord.toByteArray(), e.inviteProof.toByteArray())
        if (e.version.toInt() > settings.entryVersion()) settings.setEntryVersion(e.version.toInt())
        if (!settings.profilePending() && e.version.toInt() >= settings.entryVersion() &&
            e.displayName != accounts.require().displayName) {
            accountDao.setDisplayName(e.displayName)
            accounts.reload()
        }
    }

    companion object {
        /** SYSTEM_MEMBER system_data values (D104). */
        const val REVOKED = "revoked"
        const val DUPLICATE = "duplicate_invite"
        private val log = Logger.getLogger("DirectorySync")
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

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
