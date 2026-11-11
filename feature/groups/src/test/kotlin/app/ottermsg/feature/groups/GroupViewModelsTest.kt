package app.ottermsg.feature.groups

import androidx.lifecycle.SavedStateHandle
import app.ottermsg.core.data.group.GroupChangeRefused
import app.ottermsg.core.data.group.GroupStore
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelsTest {
    private fun id(n: Int) = ByteArray(16) { n.toByte() }
    private val me = id(1); private val boris = id(2); private val vera = id(3); private val gid = id(9)
    private fun contact(i: ByteArray, name: String, user: String) =
        ContactEntity(i, user, name, ByteArray(32), ByteArray(33), false, false, false, 1)

    private class FakeStore(val me: ByteArray) : GroupStore {
        val group = MutableStateFlow<GroupEntity?>(null)
        val members = MutableStateFlow<List<GroupMemberEntity>>(emptyList())
        val contacts = MutableStateFlow<List<ContactEntity>>(emptyList())
        val calls = mutableListOf<String>()
        var refuse = false
        override fun observeGroup(groupId: ByteArray) = group
        override fun observeMembers(groupId: ByteArray) = members
        override fun observeContacts() = contacts
        override suspend fun myId() = me
        override suspend fun conversationOf(groupId: ByteArray) = 5L
        private fun call(s: String) { if (refuse) throw GroupChangeRefused("no"); calls += s }
        override suspend fun create(name: String, family: Boolean, members: List<ByteArray>): Long { call("create:$name:$family:${members.size}"); return 42 }
        override suspend fun addMembers(groupId: ByteArray, users: List<ByteArray>) = call("add:${users.size}")
        override suspend fun removeMember(groupId: ByteArray, user: ByteArray) = call("remove:${user[0]}")
        override suspend fun rename(groupId: ByteArray, name: String) = call("rename:$name")
        override suspend fun setAdmin(groupId: ByteArray, user: ByteArray, admin: Boolean) = call("admin:${user[0]}:$admin")
        override suspend fun leave(groupId: ByteArray) = call("leave")
    }

    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStore(me)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store.contacts.value = listOf(contact(boris, "Борис", "boris"), contact(vera, "Вера", "vera"))
    }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun groupOf(vararg ms: Pair<ByteArray, Boolean>, iAmMember: Boolean = true) {
        store.group.value = GroupEntity(gid, "Семья", true, 3, byteArrayOf(), null, iAmMember, me)
        store.members.value = ms.map { GroupMemberEntity(gid, it.first, it.second) }
    }

    @Test fun newGroupNeedsNameAndMember() = runTest(dispatcher) {
        val vm = NewGroupViewModel(store)
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        assertFalse(vm.ui.value.canCreate)
        vm.onName("  Дача  "); runCurrent()
        assertFalse(vm.ui.value.canCreate)
        vm.toggle(boris); runCurrent()
        assertTrue(vm.ui.value.canCreate)
        vm.onQuery("вер"); runCurrent()
        assertEquals(listOf("Вера"), vm.ui.value.contacts.map { it.name })
        vm.onQuery("@BOR"); runCurrent()
        assertEquals(listOf(true), vm.ui.value.contacts.map { it.selected })
        vm.onFamily(true)
        var opened = -1L
        vm.create { opened = it }; runCurrent()
        assertEquals(listOf("create:Дача:true:1"), store.calls)
        assertEquals(42L, opened)
        vm.onName("я".repeat(80)); runCurrent()
        assertEquals(NewGroupUi.MAX_NAME, vm.ui.value.name.length)
    }

    private fun infoVm() = GroupInfoViewModel(store, SavedStateHandle(mapOf(GroupInfoViewModel.ARG_GROUP to gid.toHex())))

    @Test fun adminSeesActionsAndMustHandOverBeforeLeaving() = runTest(dispatcher) {
        groupOf(me to true, boris to false, vera to false)
        val vm = infoVm(); vm.meLabel.value = "Вы"
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        val ui = vm.ui.value
        assertEquals(3, ui.memberCount)
        assertEquals(listOf("Вы", "Борис", "Вера"), ui.members.map { it.name })
        assertTrue(ui.iAmAdmin); assertTrue(ui.mustPickAdminBeforeLeaving)
        vm.leaveAfterPromoting(vera); runCurrent()
        assertEquals(listOf("admin:3:true", "leave"), store.calls)
    }

    @Test fun nonAdminAndRemovedViews() = runTest(dispatcher) {
        groupOf(me to false, boris to true)
        val vm = infoVm()
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        assertFalse(vm.ui.value.iAmAdmin); assertFalse(vm.ui.value.mustPickAdminBeforeLeaving); assertTrue(vm.ui.value.iAmMember)
        groupOf(boris to true, iAmMember = false); runCurrent()
        assertFalse(vm.ui.value.iAmMember); assertFalse(vm.ui.value.iAmAdmin)
        assertEquals(1, vm.ui.value.memberCount)
    }

    @Test fun refusedActionSetsError() = runTest(dispatcher) {
        groupOf(me to true, boris to false)
        val vm = infoVm()
        store.refuse = true
        vm.rename("X"); runCurrent()
        assertTrue(vm.error.value)
        store.refuse = false
        vm.remove(boris); runCurrent()
        assertFalse(vm.error.value)
        assertEquals(listOf("remove:2"), store.calls)
    }

    @Test fun addMembersOffersOnlyNonMembers() = runTest(dispatcher) {
        groupOf(me to true, boris to false)
        val vm = AddMembersViewModel(store, SavedStateHandle(mapOf(GroupInfoViewModel.ARG_GROUP to gid.toHex())))
        backgroundScope.launch { vm.ui.collect {} }
        runCurrent()
        assertEquals(listOf("Вера"), vm.ui.value.contacts.map { it.name })
        assertFalse(vm.ui.value.canAdd)
        vm.toggle(vera); runCurrent()
        var done = false
        vm.add { done = true }; runCurrent()
        assertEquals(listOf("add:1"), store.calls); assertTrue(done)
    }
}
