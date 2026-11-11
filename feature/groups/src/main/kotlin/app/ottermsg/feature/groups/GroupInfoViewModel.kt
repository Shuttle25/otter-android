package app.ottermsg.feature.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.group.GroupStore
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

data class MemberUi(val id: ByteArray, val name: String, val admin: Boolean, val isMe: Boolean)

data class GroupInfoUi(
    val loaded: Boolean = false, val groupId: ByteArray = ByteArray(0), val name: String = "", val family: Boolean = false,
    val members: List<MemberUi> = emptyList(), val iAmAdmin: Boolean = false, val iAmMember: Boolean = true,
) {
    /** Real member count, shown apart from the art (D34). */
    val memberCount: Int get() = members.size
    /** D33: the last admin must hand over before leaving while others remain. */
    val mustPickAdminBeforeLeaving: Boolean get() = iAmAdmin && members.count { it.admin } == 1 && members.size > 1
}

/** Pure mapping, unit-tested: me first, then admins, then by name. */
fun groupInfoUi(g: GroupEntity?, members: List<GroupMemberEntity>, contacts: List<ContactEntity>, me: ByteArray, meLabel: String): GroupInfoUi {
    if (g == null) return GroupInfoUi(loaded = true, iAmMember = false)
    val names = contacts.associate { it.userId.toList() to it.displayName }
    val list = members.map { m ->
        val isMe = m.userId.contentEquals(me)
        MemberUi(m.userId, if (isMe) meLabel else names[m.userId.toList()] ?: "?", m.admin, isMe)
    }.sortedWith(compareBy<MemberUi>({ !it.isMe }, { !it.admin }, { it.name.lowercase() }))
    val mine = list.firstOrNull { it.isMe }
    return GroupInfoUi(true, g.groupId, g.name, g.family, if (g.iAmMember) list else list.filterNot { it.isMe },
        iAmAdmin = g.iAmMember && mine?.admin == true, iAmMember = g.iAmMember && mine != null)
}

@HiltViewModel
class GroupInfoViewModel @Inject constructor(private val groups: GroupStore, savedState: SavedStateHandle) : ViewModel() {
    val groupId: ByteArray = checkNotNull(savedState.get<String>(ARG_GROUP)) { "group id missing" }.hexToBytes()
    /** Label for the local user in the member list; set by the screen from resources. */
    val meLabel = MutableStateFlow("")
    private val _error = MutableStateFlow(false)
    /** The last action was refused (e.g. rights changed meanwhile); the screen shows a short note. */
    val error: StateFlow<Boolean> = _error

    val ui: StateFlow<GroupInfoUi> = combine(
        groups.observeGroup(groupId), groups.observeMembers(groupId), groups.observeContacts(), flow { emit(groups.myId()) }, meLabel,
    ) { g, ms, cs, me, label -> groupInfoUi(g, ms, cs, me, label) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupInfoUi())

    fun rename(name: String) = act { groups.rename(groupId, name) }
    fun setAdmin(user: ByteArray, admin: Boolean) = act { groups.setAdmin(groupId, user, admin) }
    fun remove(user: ByteArray) = act { groups.removeMember(groupId, user) }
    fun leave() = act { groups.leave(groupId) }
    /** Last admin leaving: promote [newAdmin] first, then leave (two signed updates). */
    fun leaveAfterPromoting(newAdmin: ByteArray) = act { groups.setAdmin(groupId, newAdmin, true); groups.leave(groupId) }
    fun dismissError() { _error.value = false }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); _error.value = false } catch (e: CancellationException) { throw e } catch (e: Exception) { _error.value = true }
        }
    }

    companion object { const val ARG_GROUP = "groupId" }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
