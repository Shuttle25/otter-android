package app.ottermsg.core.data.group

import androidx.room.withTransaction
import app.ottermsg.core.crypto.GroupRules
import app.ottermsg.core.crypto.GroupRules.Verdict
import app.ottermsg.core.crypto.GroupSigning
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.dao.GroupDao
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.PendingGroupUpdateEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.NodeApi
import app.ottermsg.proto.v1.GroupState
import app.ottermsg.proto.v1.SignedGroupState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Applies signed group states, local and incoming alike: verify → [GroupRules] → store state, members, conversation
 * and system events, or park (D36) / drop. After every accepted state the parked updates of that group are retried.
 */
@Singleton
class GroupApplier @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val directory: DirectorySync, private val clock: Clock,
) {
    private val groups: GroupDao = db.groupDao()
    private val contacts = db.contactDao()
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val rng = SecureRandom()
    private val lock = Mutex()

    /** Current accepted state of a group, or null. */
    suspend fun current(groupId: ByteArray): GroupRules.Applied? = groups.get(groupId)?.let { applied(it) }

    /** Applies [signed] received from [from]; [api] (if online) lets an unknown signer be looked up once. */
    suspend fun apply(signed: SignedGroupState, from: ByteArray, api: NodeApi? = null): Result = lock.withLock { applyLocked(signed, from, api) }

    /**
     * Local change: builds the next state from the current one under the lock, signs it and applies it like an incoming
     * update. Throws [GroupChangeRefused] when the rules would reject it (e.g. a non-admin rename).
     */
    suspend fun change(groupId: ByteArray?, build: (current: GroupState?) -> GroupState): Pair<SignedGroupState, GroupState?> = lock.withLock {
        val me = accounts.require()
        val cur = groupId?.let { current(it) }
        val next = build(cur?.state)
        val prev = groups.get(next.groupId.toByteArray())?.prevState?.let { GroupState.parseFrom(it) }
        val verdict = GroupRules.evaluate(cur, prev, next, me.userId)
        if (verdict != Verdict.Accept) throw GroupChangeRefused(verdict.toString())
        val signed = GroupSigning.sign(next, me.accountKey)
        val r = applyLocked(signed, me.userId, null)
        check(r.verdict == Verdict.Accept) { "local change not accepted: ${r.verdict}" }
        signed to cur?.state
    }

    class Result(val verdict: Verdict, val groupId: ByteArray?, /** Groups with a newly accepted state (incl. unparked updates). */ val accepted: List<ByteArray>)

    private suspend fun applyLocked(signed: SignedGroupState, from: ByteArray, api: NodeApi?): Result {
        val state = runCatching { GroupState.parseFrom(signed.state) }.getOrNull() ?: return Result(Verdict.Reject("malformed"), null, emptyList())
        val gid = state.groupId.toByteArray()
        val signer = signed.signer.toByteArray()
        val pub = signerKey(signer, api)
        if (pub == null) {
            park(gid, signed, from)
            return Result(Verdict.Park("unknown signer"), gid, emptyList())
        }
        if (!GroupSigning.verify(signed, pub)) return Result(Verdict.Reject("bad signature"), gid, emptyList())
        val verdict = evaluateAndStore(signed, state, signer, from)
        val accepted = mutableListOf<ByteArray>()
        if (verdict == Verdict.Accept) {
            accepted += gid
            retryParked(gid)
        }
        if (verdict != Verdict.Accept) log.fine("group update ${verdict}")
        return Result(verdict, gid, accepted)
    }

    private suspend fun signerKey(signer: ByteArray, api: NodeApi?): ByteArray? {
        val me = accounts.require()
        if (signer.contentEquals(me.userId)) return me.accountKey.publicKey
        contacts.get(signer)?.let { return it.accountPub }
        if (api == null) return null
        try { directory.sync(api) } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        return contacts.get(signer)?.accountPub
    }

    private suspend fun park(gid: ByteArray, signed: SignedGroupState, from: ByteArray) {
        groups.insertPendingUpdate(PendingGroupUpdateEntity(groupId = gid, signed = signed.toByteArray(), fromUser = from, receivedMs = clock.now()))
    }

    /** Verdict for [state]; on Accept it is stored with its members, conversation and events in one transaction. */
    private suspend fun evaluateAndStore(signed: SignedGroupState, state: GroupState, signer: ByteArray, from: ByteArray, parkIt: Boolean = true): Verdict {
        val gid = state.groupId.toByteArray()
        val me = accounts.require().userId
        return db.withTransaction {
            val row = groups.get(gid)
            val cur = row?.let { applied(it) }
            val prev = row?.prevState?.let { GroupState.parseFrom(it) }
            val verdict = GroupRules.evaluate(cur, prev, state, signer, signed.state.toByteArray())
            when (verdict) {
                is Verdict.Park -> { if (parkIt) park(gid, signed, from); return@withTransaction verdict }
                is Verdict.Reject -> return@withTransaction verdict
                Verdict.Accept -> {}
            }
            val old = cur?.state
            // A concurrent winner at the same version replaces the loser but keeps the common previous state (D37).
            val newPrev = if (old != null && old.version == state.version) row.prevState else old?.toByteArray()
            val iAmMember = state.membersList.any { it.toByteArray().contentEquals(me) }
            groups.upsert(GroupEntity(gid, state.name, state.family, state.version, signed.toByteArray(), newPrev, iAmMember,
                state.createdBy.toByteArray()))
            val admins = state.adminsList.map { it.toByteArray().toList() }.toSet()
            groups.replaceMembers(gid, state.membersList.map { GroupMemberEntity(gid, it.toByteArray(), it.toByteArray().toList() in admins) })
            val now = clock.now()
            val cid = conversations.getOrCreateGroupConversation(gid, state.name, now)
            conversations.setGroupTitle(gid, state.name)
            for (e in GroupEvents.between(old, state, signer, me)) {
                messages.insert(MessageEntity(messageId = ByteArray(16).also(rng::nextBytes), conversationId = cid, senderId = signer,
                    outgoing = false, sentMs = now, receivedMs = now, body = "", status = MessageStatus.DELIVERED,
                    kind = MessageKind.SYSTEM_GROUP, systemData = e.toByteArray()))
            }
            conversations.touch(cid, now)
            // Rotation: anyone removed (or left) ⇒ our next group message uses a fresh sender key shared with the rest.
            val removedSomeone = old != null && old.membersList.any { it !in state.membersList }
            if (removedSomeone) {
                groups.deleteMyDistribution(gid)
                groups.clearShared(gid)
            }
            verdict
        }
    }

    /** Retries parked updates of [gid] until none is accepted in a full pass (D36); expired ones are dropped. */
    private suspend fun retryParked(gid: ByteArray) {
        groups.prunePendingUpdates(clock.now() - GroupDao.PENDING_TTL_MS)
        while (true) {
            var progress = false
            for (p in groups.pendingUpdates(gid)) {
                val signed = runCatching { SignedGroupState.parseFrom(p.signed) }.getOrNull()
                val state = signed?.let { runCatching { GroupState.parseFrom(it.state) }.getOrNull() }
                val pub = signed?.let { signerKey(it.signer.toByteArray(), null) }
                if (signed == null || state == null) { groups.deletePendingUpdate(p.id); continue }
                if (pub == null) continue
                if (!GroupSigning.verify(signed, pub)) { groups.deletePendingUpdate(p.id); continue }
                when (evaluateAndStore(signed, state, signed.signer.toByteArray(), p.fromUser, parkIt = false)) {
                    Verdict.Accept -> { groups.deletePendingUpdate(p.id); progress = true }
                    is Verdict.Park -> {} // stays parked; its 30-day clock keeps running from the first receipt
                    is Verdict.Reject -> groups.deletePendingUpdate(p.id)
                }
            }
            if (!progress) return
        }
    }

    private fun applied(row: GroupEntity): GroupRules.Applied {
        val signed = SignedGroupState.parseFrom(row.stateSigned)
        return GroupRules.Applied(GroupState.parseFrom(signed.state), signed.signer.toByteArray(), signed.state.toByteArray())
    }

    companion object { private val log = Logger.getLogger("GroupApplier") }
}

class GroupChangeRefused(reason: String) : Exception(reason)
