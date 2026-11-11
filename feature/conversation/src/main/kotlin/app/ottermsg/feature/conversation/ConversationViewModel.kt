package app.ottermsg.feature.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.data.messaging.GroupView
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.ui.components.parseGroupEvent
import app.ottermsg.core.database.model.MessageKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

data class ConversationUi(
    /** False until the first database emission (the empty-chat art must not flash while loading). */
    val loaded: Boolean = false,
    val title: String = "", val seed: ByteArray = ByteArray(0), val items: List<ConversationItem> = emptyList(), val lastIncomingId: Long? = null,
    val isGroup: Boolean = false, val family: Boolean = false, val groupIdHex: String = "",
    /** Real member count of a group (subtitle "N участников"). */
    val memberCount: Int = 0,
    /** False for a group we are no longer a member of: the composer is replaced by a notice. */
    val canWrite: Boolean = true,
    val me: ByteArray = ByteArray(0),
    /** Display names by user id hex, for group events. */
    val names: Map<String, String> = emptyMap(),
) {
    fun nameOf(id: ByteArray): String = names[id.hex()] ?: "?"
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** Pure mapping, unit-tested. */
fun conversationUi(
    conv: ConversationEntity?, msgs: List<MessageEntity>, group: GroupView?, contacts: List<ContactEntity>, me: ByteArray, zone: ZoneId,
): ConversationUi {
    val title = conv?.title.orEmpty()
    val names = contacts.associate { it.userId.hex() to it.displayName }
    val isGroup = conv?.groupId != null
    val list = msgs.map {
        val author = when {
            it.outgoing -> ""
            isGroup -> names[it.senderId.hex()] ?: "?"
            else -> title
        }
        MessageUi(it.localId, it.outgoing, it.body, it.sentMs, it.status, it.kind == MessageKind.SYSTEM_UNDECRYPTABLE, author,
            authorKey = if (it.outgoing) "me" else it.senderId.hex(),
            event = if (it.kind == MessageKind.SYSTEM_GROUP) parseGroupEvent(it.systemData) else null,
            inGroup = isGroup && !it.outgoing)
    }
    return ConversationUi(true, title, conv?.peerUserId ?: conv?.groupId ?: ByteArray(0), groupMessages(list, zone),
        msgs.lastOrNull { !it.outgoing && it.kind != MessageKind.SYSTEM_GROUP }?.localId,
        isGroup = isGroup, family = group?.group?.family == true, groupIdHex = conv?.groupId?.hex().orEmpty(),
        memberCount = group?.members?.size ?: 0,
        canWrite = !isGroup || group?.group?.iAmMember == true,
        me = me, names = names)
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val store: ConversationStore, savedState: SavedStateHandle,
) : ViewModel() {
    val conversationId: Long = checkNotNull(savedState.get<Long>(ARG_ID)) { "conversation id missing" }
    var zone: ZoneId = ZoneId.systemDefault()

    val ui: StateFlow<ConversationUi> = combine(
        store.observeConversation(conversationId), store.observeMessages(conversationId), store.observeGroup(conversationId),
        store.observeContacts(), flow { emit(store.myId()) },
    ) { conv, msgs, group, contacts, me -> conversationUi(conv, msgs, group, contacts, me, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUi())

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending
    private val _sent = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emits after an own message was stored (the screen scrolls to the bottom). */
    val sent: SharedFlow<Long> = _sent

    init {
        viewModelScope.launch {
            val draft = store.observeConversation(conversationId).filterNotNull().first().draft
            if (_text.value.isEmpty()) _text.value = draft
            _text.drop(1).debounce(DRAFT_DEBOUNCE_MS).collect { store.setDraft(conversationId, it) }
        }
    }

    fun onText(v: String) { _text.value = v }

    /** The field is cleared only after the message is safely in the local queue; double taps are ignored. */
    fun send() {
        val t = _text.value
        if (t.isBlank() || _sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                val id = store.sendText(conversationId, t)
                if (id != null) {
                    if (_text.value == t) _text.value = ""
                    _sent.tryEmit(id)
                }
            } finally {
                _sending.value = false
            }
        }
    }

    fun retry(localId: Long) = viewModelScope.launch { store.retry(localId) }
    fun delete(localId: Long) = viewModelScope.launch { store.deleteLocal(localId) }
    fun markRead() = viewModelScope.launch { store.markConversationRead(conversationId) }

    companion object {
        const val ARG_ID = "id"
        const val DRAFT_DEBOUNCE_MS = 500L
    }
}
