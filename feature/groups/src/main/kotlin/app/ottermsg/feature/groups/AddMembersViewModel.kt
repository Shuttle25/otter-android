package app.ottermsg.feature.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.group.GroupStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddMembersUi(val query: String = "", val contacts: List<PickContact> = emptyList(), val selectedCount: Int = 0, val busy: Boolean = false) {
    val canAdd: Boolean get() = selectedCount > 0 && !busy
}

/** Contacts not in the group yet; multi-select; one signed update adds them all. */
@HiltViewModel
class AddMembersViewModel @Inject constructor(private val groups: GroupStore, savedState: SavedStateHandle) : ViewModel() {
    private val groupId = checkNotNull(savedState.get<String>(GroupInfoViewModel.ARG_GROUP)).hexToBytes()
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<List<Byte>>>(emptySet())
    private val busy = MutableStateFlow(false)

    val ui: StateFlow<AddMembersUi> = combine(groups.observeContacts(), groups.observeMembers(groupId), query, selected, busy) { cs, ms, q, sel, b ->
        val inGroup = ms.map { it.userId.toList() }.toSet()
        AddMembersUi(q, pickList(cs.filter { it.userId.toList() !in inGroup }, sel, q), sel.size, b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddMembersUi())

    fun onQuery(v: String) { query.value = v }
    fun toggle(id: ByteArray) { selected.value = selected.value.let { s -> if (id.toList() in s) s - listOf(id.toList()) else s + listOf(id.toList()) } }

    fun add(onDone: () -> Unit) {
        if (!ui.value.canAdd) return
        busy.value = true
        viewModelScope.launch {
            try { groups.addMembers(groupId, selected.value.map { it.toByteArray() }); onDone() } finally { busy.value = false }
        }
    }
}
