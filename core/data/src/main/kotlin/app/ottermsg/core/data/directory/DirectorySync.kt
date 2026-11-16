package app.ottermsg.core.data.directory

import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SigContext
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
    private val messages: MessageDao, private val clock: Clock, private val rekeys: RekeyApplier,
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
     * revoked (sticky); of several redemptions of one invite the lowest SHA-256(invite_proof) stays (D102), and the
     * losers plus every contact whose invite chain passes through one are revoked, even contacts accepted while the
     * loser still looked like a normal member (as the node's disableDuplicates does). A network-signed REKEY (D111)
     * revokes its old account like a REVOCATION; the winning REKEY's new account is admitted without an invite chain,
     * is never disabled as a duplicate, and the old account's chat moves to it ([RekeyApplier]).
     */
    suspend fun apply(records: List<Record>) = mutex.withLock {
        val me = accounts.require()
        val networkPub = settings.networkPub()
        val ctx = accounts.sigContext() // D109
        val founding = manifests.latest()?.let { runCatching { ManifestVerifier.verify(it.raw, networkPub, 0, 0) }.getOrNull() }
            ?.foundingMembersList?.map { it.toByteArray().hex() }?.toSet().orEmpty()
        val revokedAt = HashMap<String, Long>() // earliest network-signed revocation per user
        val forRekey = HashMap<String, Long>() // revoked for a re-key (D111 step 1): groups wait for the REKEY (D112)
        val rights = HashMap<String, MutableList<RightsRecord>>()
        for (rec in records) {
            Membership.revocation(rec, networkPub, ctx)?.let { r ->
                revokedAt.merge(r.userId.hex(), r.createdMs, ::minOf)
                if (r.reason == REKEY_REASON) forRekey.merge(r.userId.hex(), r.createdMs, ::minOf)
            }
            Membership.rights(rec, networkPub, ctx)?.let { r -> rights.getOrPut(r.userId.hex()) { mutableListOf() } += r }
        }
        // D111: every stored REKEY revokes its old account (so it never invites after it); the winner's new account is
        // confirmed by the admin: no invite chain needed, never a duplicate loser (as the node's admitIfNew/duplicates)
        val stored = rekeys.store(records, networkPub, ctx)
        stored.forEach { k -> revokedAt.merge(k.oldUserId.hex(), k.revokedMs, ::minOf) }
        val rekeyed = stored.map { it.oldUserId.hex() }.toSet()
        val successors = stored.map { it.newUserId.hex() }.toSet()
        val known = contacts.all().associateBy { it.userId.hex() }
        val entries = records.mapNotNull { verifyEntry(it, ctx) }
        // Losers of a doubly redeemed invite, and every account invited through one at any depth, are disabled (like
        // the node's disableDuplicates): never inviters, and revoked if already contacts. Finding them can drop entries
        // they invited, which can change other groups, so repeat until stable (both sets only grow, so this ends).
        // Losers and the inviter edges are kept in Settings, so the result does not depend on what a node shows when.
        val losers = HashMap<String, Redemption?>()
        val prevLosers = settings.duplicateLosers()
        prevLosers.filter { it !in successors }.forEach { losers[it] = null }
        val inviters = HashMap<String, MutableSet<String>>()
        settings.contactInviters().forEach { (u, v) -> inviters[u] = v.toMutableSet() }
        // a successor revoked here only as a duplicate before its REKEY arrived is re-enabled (as on the nodes)
        val reenabled = disabledBy(prevLosers, inviters, emptySet()).filter { it in successors && it !in revokedAt }.toSet()
        var disabled = disabledBy(losers.keys, inviters, successors)
        var accepted: List<Pair<DirectoryEntry, Redemption?>>
        while (true) {
            accepted = admit(entries, me, networkPub, ctx, founding, known, revokedAt, rights, disabled, successors, reenabled)
            // inviter edges of accepted entries, whether or not the inviter may still invite
            val keys = HashMap<String, ByteArray>()
            keys[me.userId.hex()] = me.accountKey.publicKey
            known.forEach { (hex, c) -> keys[hex] = c.accountPub }
            accepted.forEach { (e, _) -> keys[e.userId.toByteArray().hex()] = e.accountPub.toByteArray() }
            for ((e, _) in accepted) {
                val r = Membership.chain(e, networkPub, ctx) { a -> keys[a.hex()] } ?: continue
                if (!r.fromNetwork) inviters.getOrPut(e.userId.toByteArray().hex()) { mutableSetOf() } += r.inviter.hex()
            }
            val fresh = accepted.mapNotNull { (e, r) -> r?.let { e to it } }.groupBy { it.second.inviteId.hex() }.values
                .filter { it.size > 1 }
                .flatMap { g ->
                    g.sortedWith { a, b ->
                        Membership.compareUnsigned(a.second.rank, b.second.rank).takeIf { it != 0 }
                            ?: Membership.compareUnsigned(a.first.userId.toByteArray(), b.first.userId.toByteArray())
                    }.drop(1)
                }
                .filter { (e, _) -> e.userId.toByteArray().hex().let { it !in losers && it !in successors } }
            fresh.forEach { (e, r) -> losers[e.userId.toByteArray().hex()] = r }
            val next = disabledBy(losers.keys, inviters, successors)
            if (fresh.isEmpty() && next == disabled) break
            disabled = next
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
            val revoked = (old?.revoked == true && userId.hex() !in reenabled) || userId.hex() in revokedAt || userId.hex() in disabled
            contacts.upsert(ContactEntity(
                userId = userId, username = e.username, displayName = e.displayName,
                accountPub = e.accountPub.toByteArray(), identityPub = identity,
                verified = (old?.verified ?: false) && !changed,
                identityChanged = (old?.identityChanged ?: false) || changed,
                revoked = revoked, entryVersion = e.version, resetMs = old?.resetMs ?: 0,
            ))
            if (old == null || old.displayName != e.displayName) conversations.setTitle(userId, e.displayName)
            if (revoked && old?.revoked != true && userId.hex() !in rekeyed) { // a re-key shows its own line
                // the inviter of a doubly used link learns which account was disabled; a removal shows in an open chat
                if (dup != null) { if (dup.inviter.contentEquals(me.userId)) memberLine(userId, e.displayName, DUPLICATE, create = true) }
                else memberLine(userId, e.displayName, REVOKED, create = false)
            }
        }
        // a revocation, or a duplicate loser upstream, of a contact whose entry is not in this batch
        for (c in contacts.all()) {
            if (c.revoked || (c.userId.hex() !in revokedAt && c.userId.hex() !in disabled)) continue
            contacts.upsert(c.copy(revoked = true))
            if (c.userId.hex() !in rekeyed) memberLine(c.userId, c.displayName, REVOKED, create = false)
        }
        // our own network-signed REVOCATION (or our redemption lost a doubly used invite, as on every node): disabled
        if (me.userId.hex() in revokedAt || me.userId.hex() in disabled) settings.setAccountRevoked(true)
        settings.addRekeyRevocations(forRekey)
        settings.setDuplicateLosers(losers.keys)
        val contactIds = contacts.all().map { it.userId.hex() }.toSet()
        settings.setContactInviters(inviters.filterKeys { it in contactIds })
        rekeys.applyPending(me.userId) // D111: chats move once the new account's entry is known
    }

    /**
     * [losers] and every account whose inviter chain passes through one, at any depth (the node's disableDuplicates),
     * except [exempt] (the new accounts of winning REKEYs, D111), which are never disabled and never pass it on.
     */
    private fun disabledBy(losers: Set<String>, inviters: Map<String, Set<String>>, exempt: Set<String>): Set<String> {
        val out = losers.filterTo(mutableSetOf()) { it !in exempt }
        var grew = true
        while (grew) {
            grew = false
            for ((u, by) in inviters) if (u !in out && u !in exempt && by.any { it in out }) { out += u; grew = true }
        }
        return out
    }

    /**
     * The entries this phone accepts: known contacts and ourselves, founding members, the new accounts of winning REKEYs
     * ([successors], D111), and entries whose invite chain verifies (fixpoint over the batch: an inviter may come later).
     */
    private fun admit(
        entries: List<DirectoryEntry>, me: LocalAccount, networkPub: ByteArray, ctx: SigContext, founding: Set<String>,
        known: Map<String, ContactEntity>, revokedAt: Map<String, Long>, rights: Map<String, List<RightsRecord>>,
        disabled: Set<String>, successors: Set<String>, reenabled: Set<String>,
    ): List<Pair<DirectoryEntry, Redemption?>> {
        val keys = HashMap<String, ByteArray>()
        keys[me.userId.hex()] = me.accountKey.publicKey
        known.forEach { (hex, c) -> keys[hex] = c.accountPub }
        /** Whether the inviter of [r] could invite when the invite was signed (Go admitChain, D99/D101). */
        fun inviterAllowed(r: Redemption): Boolean {
            if (r.fromNetwork) return true
            val a = r.inviter.hex()
            if (a in disabled) return false // a duplicate loser or invited through one: its invites never admit anyone
            val at = revokedAt[a]
            if (at != null && at <= r.createdMs) return false
            // revoked here but no dated revocation in this batch (a duplicate loser, or a hidden record): not an inviter
            if (at == null && known[a]?.revoked == true && a !in reenabled) return false
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
                val red = Membership.chain(e, networkPub, ctx) { a -> keys[a.hex()] }?.takeIf(::inviterAllowed)
                if (red != null || keys.containsKey(id) || id in founding || id in successors) {
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
        /** REVOCATION reason written by `otter-admin user rekey --old` (otter-server internal/admin). */
        const val REKEY_REASON = "rekey"
        private val log = Logger.getLogger("DirectorySync")
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

        /**
         * The entry if [rec] is a self-certifying, correctly bound DIRECTORY_ENTRY (sync and words restore, C18), signed
         * for this network under [ctx] (D109).
         */
        fun verifyEntry(rec: Record, ctx: SigContext): DirectoryEntry? = runCatching {
            val body = RecordBody.parseFrom(rec.body)
            if (body.type != RecordType.DIRECTORY_ENTRY) return null
            val e = DirectoryEntry.parseFrom(body.payload)
            val accountPub = e.accountPub.toByteArray()
            val ok = accountPub.size == 32 &&
                body.author == e.userId &&
                e.userId.toByteArray().contentEquals(Ids.id16(accountPub)) &&
                RecordCodec.verify(rec, accountPub, ctx) &&
                IdentityKeys.verifyBinding(e.identityPub.toByteArray(), accountPub, e.identitySig.toByteArray())
            if (ok) e else null
        }.getOrNull()
    }
}
