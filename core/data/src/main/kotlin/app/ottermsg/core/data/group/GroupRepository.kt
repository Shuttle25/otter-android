package app.ottermsg.core.data.group

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.messaging.Outbox
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.GroupState
import app.ottermsg.proto.v1.GroupUpdate
import app.ottermsg.proto.v1.SignedGroupState
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** What the group screens need (seam for ViewModel tests). */
interface GroupStore {
    fun observeGroup(groupId: ByteArray): Flow<GroupEntity?>
    fun observeMembers(groupId: ByteArray): Flow<List<GroupMemberEntity>>
    /** Creates the group and returns its conversation id. */
    suspend fun create(name: String, family: Boolean, members: List<ByteArray>): Long
    suspend fun addMembers(groupId: ByteArray, users: List<ByteArray>)
    suspend fun removeMember(groupId: ByteArray, user: ByteArray)
    suspend fun rename(groupId: ByteArray, name: String)
    suspend fun setAdmin(groupId: ByteArray, user: ByteArray, admin: Boolean)
    suspend fun leave(groupId: ByteArray)
    suspend fun conversationOf(groupId: ByteArray): Long?
    /** Family members (pickers, names). */
    fun observeContacts(): Flow<List<ContactEntity>>
    suspend fun myId(): ByteArray
    suspend fun myName(): String
}

/**
 * Local group changes (D33). Each builds the next state (version + 1), signs it, applies it locally through the same
 * [GroupApplier] as incoming updates, then queues a GroupUpdate 1:1 (urgent) to every member of the new state and to
 * every member this change removed. Works offline: the outbox sends when a node is reachable.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val applier: GroupApplier,
    private val outbox: Outbox, private val clock: Clock,
) : GroupStore {
    private val groups = db.groupDao()
    private val rng = SecureRandom()

    override fun observeGroup(groupId: ByteArray) = groups.observe(groupId)
    override fun observeMembers(groupId: ByteArray) = groups.observeMembers(groupId)
    override suspend fun conversationOf(groupId: ByteArray): Long? = db.conversationDao().byGroup(groupId)?.id
    override fun observeContacts(): Flow<List<ContactEntity>> = db.contactDao().observeAll()
    override suspend fun myId(): ByteArray = accounts.require().userId
    override suspend fun myName(): String = accounts.require().displayName

    override suspend fun create(name: String, family: Boolean, members: List<ByteArray>): Long {
        val me = accounts.require().userId
        val gid = ByteArray(16).also(rng::nextBytes)
        val all = (listOf(me) + members.filterNot { it.contentEquals(me) }).distinctBy { it.toList() }
        val (signed, _) = applier.change(null) {
            GroupState.newBuilder().setGroupId(bs(gid)).setName(name.trim()).setVersion(1).setFamily(family)
                .setCreatedBy(bs(me)).setCreatedMs(clock.now())
                .addAllMembers(all.map(::bs)).addAdmins(bs(me)).build()
        }
        distribute(signed, null)
        return checkNotNull(conversationOf(gid))
    }

    override suspend fun addMembers(groupId: ByteArray, users: List<ByteArray>) = change(groupId) { s ->
        s.toBuilder().addAllMembers(users.map(::bs).filter { it !in s.membersList }.distinct())
    }

    override suspend fun removeMember(groupId: ByteArray, user: ByteArray) = change(groupId) { s ->
        val u = bs(user)
        s.toBuilder().clearMembers().addAllMembers(s.membersList - u).clearAdmins().addAllAdmins(s.adminsList - u)
    }

    override suspend fun rename(groupId: ByteArray, name: String) = change(groupId) { it.toBuilder().setName(name.trim()) }

    override suspend fun setAdmin(groupId: ByteArray, user: ByteArray, admin: Boolean) = change(groupId) { s ->
        val u = bs(user)
        val admins = if (admin) (s.adminsList + listOf(u)).distinct() else s.adminsList - u
        s.toBuilder().clearAdmins().addAllAdmins(admins)
    }

    /** Leaving as the last admin while others remain is refused by the rules: promote someone first (D33). */
    override suspend fun leave(groupId: ByteArray) {
        val me = bs(accounts.require().userId)
        change(groupId) { s -> s.toBuilder().clearMembers().addAllMembers(s.membersList - me).clearAdmins().addAllAdmins(s.adminsList - me) }
    }

    /**
     * D112: replaces the re-keyed member [old] by its new account [new] in members and admins (the admin flag moves with
     * it), nothing else. The caller ([GroupRevocations]) decides that this phone is the one to sign it. The update is not
     * sent to [old]: that key belongs to whoever holds the lost phone. False if there is nothing to swap.
     */
    suspend fun swapMember(groupId: ByteArray, old: ByteArray, new: ByteArray): Boolean {
        val o = bs(old); val n = bs(new)
        val cur = applier.current(groupId)?.state ?: return false
        if (o !in cur.membersList || n in cur.membersList) return false
        change(groupId, skip = setOf(o)) { s ->
            if (o !in s.membersList || n in s.membersList) throw GroupChangeRefused("nothing to swap") // changed meanwhile
            fun swap(l: List<ByteString>) = l.map { if (it == o) n else it }
            s.toBuilder().clearMembers().addAllMembers(swap(s.membersList)).clearAdmins().addAllAdmins(swap(s.adminsList))
        }
        return true
    }

    private suspend fun change(groupId: ByteArray, skip: Set<ByteString> = emptySet(), edit: (GroupState) -> GroupState.Builder) {
        val (signed, old) = applier.change(groupId) { cur ->
            val s = cur ?: throw GroupChangeRefused("unknown group")
            edit(s).setVersion(s.version + 1).build()
        }
        distribute(signed, old, skip)
    }

    /** Queues the update to every member of the new state and every member it removed, except us and [skip]. */
    private suspend fun distribute(signed: SignedGroupState, old: GroupState?, skip: Set<ByteString> = emptySet()) {
        val me = bs(accounts.require().userId)
        val state = GroupState.parseFrom(signed.state)
        val removed = old?.membersList.orEmpty().filter { it !in state.membersList }
        val content = Content.newBuilder().setGroupUpdate(GroupUpdate.newBuilder().setState(signed)).setSentMs(clock.now()).build()
        for (m in (state.membersList + removed).distinct()) {
            if (m == me || m in skip) continue
            outbox.enqueue(m.toByteArray(), content, messageLocalId = null, urgent = true)
        }
    }

    /** Not the stdlib minus: ByteString is itself an Iterable<Byte>. */
    private operator fun List<ByteString>.minus(x: ByteString): List<ByteString> = filterNot { it == x }
}
