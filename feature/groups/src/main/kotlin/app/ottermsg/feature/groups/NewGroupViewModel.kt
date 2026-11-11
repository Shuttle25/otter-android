package app.ottermsg.feature.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.group.GroupStore
import app.ottermsg.core.database.entity.ContactEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PickContact(val id: ByteArray, val name: String, val username: String, val selected: Boolean)

data class NewGroupUi(
    val name: String = "", val family: Boolean = false, val query: String = "",
    val contacts: List<PickContact> = emptyList(), val selectedCount: Int = 0, val creating: Boolean = false,
) {
    val nameValid: Boolean get() = name.trim().codePointCount(0, name.trim().length) in 1..MAX_NAME
    val canCreate: Boolean get() = nameValid && selectedCount >= 1 && !creating
    companion object { const val MAX_NAME = 64 }
}

/** Pure filter used by the member pickers: by display name or username, case-insensitive. */
fun pickList(all: List<ContactEntity>, selected: Set<List<Byte>>, query: String): List<PickContact> {
    val q = query.trim().removePrefix("@").lowercase()
    return all.filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q) }
        .map { PickContact(it.userId, it.displayName, it.username, it.userId.toList() in selected) }
}

@HiltViewModel
class NewGroupViewModel @Inject constructor(private val groups: GroupStore) : ViewModel() {
    private val name = MutableStateFlow("")
    private val family = MutableStateFlow(false)
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<List<Byte>>>(emptySet())
    private val creating = MutableStateFlow(false)

    val ui: StateFlow<NewGroupUi> = combine(
        combine(name, family, query) { n, f, q -> Triple(n, f, q) }, groups.observeContacts(), selected, creating,
    ) { (n, f, q), all, sel, busy ->
        NewGroupUi(n, f, q, pickList(all, sel, q), sel.size, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewGroupUi())

    fun onName(v: String) { name.value = v.take(NewGroupUi.MAX_NAME) }
    fun onFamily(v: Boolean) { family.value = v }
    fun onQuery(v: String) { query.value = v }
    fun toggle(id: ByteArray) { selected.value = selected.value.let { s -> if (id.toList() in s) s - listOf(id.toList()) else s + listOf(id.toList()) } }

    /** Creates the group (works offline: updates go out through the outbox) and reports its conversation. */
    fun create(onCreated: (Long) -> Unit) {
        val u = ui.value
        if (!u.canCreate) return
        creating.value = true
        viewModelScope.launch {
            try {
                onCreated(groups.create(name.value.trim(), family.value, selected.value.map { it.toByteArray() }))
            } finally {
                creating.value = false
            }
        }
    }
}
