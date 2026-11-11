package app.ottermsg.feature.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.data.messaging.GroupView
import app.ottermsg.core.database.dao.RecipientStatus
import app.ottermsg.core.database.entity.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class RecipientState { PENDING, DELIVERED, READ }

data class RecipientRow(val name: String, val state: RecipientState, val timeMs: Long?, val stillInGroup: Boolean)

data class MessageInfoUi(
    val loaded: Boolean = false, val text: String = "", val sentMs: Long = 0,
    val total: Int = 0, val delivered: Int = 0, val read: Int = 0, val rows: List<RecipientRow> = emptyList(),
)

/** Pure mapping (D23): counts over the frozen recipient set; members removed later stay listed, marked. */
fun messageInfoUi(m: MessageEntity?, recipients: List<RecipientStatus>, group: GroupView?): MessageInfoUi {
    if (m == null) return MessageInfoUi(loaded = true)
    val members = group?.members?.map { it.userId.toList() }?.toSet()
    val rows = recipients.map { r ->
        val state = when { r.readMs != null -> RecipientState.READ; r.deliveredMs != null -> RecipientState.DELIVERED; else -> RecipientState.PENDING }
        RecipientRow(r.displayName ?: "?", state, r.readMs ?: r.deliveredMs, members == null || r.userId.toList() in members)
    }.sortedWith(compareBy<RecipientRow>({ it.state.ordinal }, { it.name.lowercase() }).reversed())
    return MessageInfoUi(true, m.body, m.sentMs, rows.size, rows.count { it.state != RecipientState.PENDING },
        rows.count { it.state == RecipientState.READ }, rows)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MessageInfoViewModel @Inject constructor(store: ConversationStore, savedState: SavedStateHandle) : ViewModel() {
    private val localId: Long = checkNotNull(savedState.get<Long>(ARG_LOCAL_ID))
    private val message = store.observeMessage(localId)
    private val group = message.flatMapLatest { m -> if (m == null) flowOf(null) else store.observeGroup(m.conversationId) }

    val ui: StateFlow<MessageInfoUi> = combine(message, store.observeRecipients(localId), group, ::messageInfoUi)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageInfoUi())

    companion object { const val ARG_LOCAL_ID = "localId" }
}
