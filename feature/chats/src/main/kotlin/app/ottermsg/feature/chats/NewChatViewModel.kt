package app.ottermsg.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.directory.ContactRepository
import app.ottermsg.core.data.directory.UsernameLookup
import app.ottermsg.core.data.directory.FoundPerson
import app.ottermsg.core.data.directory.PersonFinder
import app.ottermsg.core.ui.R as UiR
import app.ottermsg.core.database.entity.ContactEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The catalog filtered by name or `@username` (the group pickers' rule, D136). */
fun searchList(all: List<ContactEntity>, query: String): List<ContactEntity> {
    val q = UsernameLookup.normalize(query)
    return all.filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.username.lowercase().contains(q) }
}

/** The name «Найти @name» offers: a valid username that none of [shown] has exactly (D137); null = no row. */
fun findOffer(shown: List<ContactEntity>, query: String): String? {
    val q = UsernameLookup.normalize(query)
    return q.takeIf { UsernameLookup.VALID.matches(it) && shown.none { c -> UsernameLookup.matches(c, it) } }
}

/** The text under the search field for a lookup that found nobody (null for none). */
internal fun lookupMessage(r: UsernameLookup.Result?): Int? = when (r) {
    UsernameLookup.Result.NotFound -> UiR.string.ui_lookup_not_found
    UsernameLookup.Result.Limited -> UiR.string.ui_lookup_limited
    UsernameLookup.Result.OldNode -> UiR.string.ui_lookup_old_node
    UsernameLookup.Result.Offline -> UiR.string.ui_lookup_offline
    is UsernameLookup.Result.Found, null -> null
}

@HiltViewModel
class NewChatViewModel @Inject constructor(contacts: ContactRepository, private val people: PersonFinder) : ViewModel() {
    val contacts: StateFlow<List<ContactEntity>?> = contacts.observeContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    /** The catalog filtered by [query]; null until the store answers. */
    val shown: StateFlow<List<ContactEntity>?> = combine(this.contacts, _query) { all, q -> all?.let { searchList(it, q) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _person = MutableStateFlow<FoundPerson?>(null)
    val person: StateFlow<FoundPerson?> = _person
    private val _failed = MutableStateFlow<UsernameLookup.Result?>(null)
    /** The last lookup that found nobody (shown under the field until the query changes). */
    val failed: StateFlow<UsernameLookup.Result?> = _failed
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    fun onQuery(q: String) { _query.value = q; _failed.value = null }

    /** «Найти @name»: the exact lookup (D137); a hit opens the person card. */
    fun find() {
        if (_searching.value) return
        val q = _query.value
        viewModelScope.launch {
            _searching.value = true
            try {
                val (found, r) = people.find(q)
                if (found != null) _person.value = found else _failed.value = r
            } finally { _searching.value = false }
        }
    }

    fun dismissPerson() { _person.value = null }

    fun open(userId: ByteArray, onOpened: (Long) -> Unit) {
        viewModelScope.launch { onOpened(people.chatWith(userId)) }
    }
}
