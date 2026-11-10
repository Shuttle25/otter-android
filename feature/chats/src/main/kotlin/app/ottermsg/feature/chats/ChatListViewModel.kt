package app.ottermsg.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.model.ConversationSummary
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.network.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class ChatRow(
    val id: Long, val title: String,
    /** Preview text, or null for an empty conversation. */
    val preview: String?,
    val previewIsSystem: Boolean, val outgoing: Boolean, val status: Int?,
    val time: String, val unread: Int,
)

/** Pure mapping, unit-tested: time "HH:mm" today, "d MMM" otherwise, in the device locale. */
fun chatRows(list: List<ConversationSummary>, nowMs: Long, zone: ZoneId, locale: Locale): List<ChatRow> {
    val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    val hm = DateTimeFormatter.ofPattern("HH:mm", locale)
    val dm = DateTimeFormatter.ofPattern("d MMM", locale)
    return list.map { s ->
        val t = Instant.ofEpochMilli(s.lastActivityMs).atZone(zone)
        ChatRow(
            id = s.id, title = s.title,
            preview = if (s.lastKind == MessageKind.SYSTEM_UNDECRYPTABLE) "" else s.lastBody,
            previewIsSystem = s.lastKind == MessageKind.SYSTEM_UNDECRYPTABLE,
            outgoing = s.lastOutgoing == true,
            status = if (s.lastOutgoing == true) s.lastStatus else null,
            time = if (t.toLocalDate() == today) hm.format(t) else dm.format(t),
            unread = s.unread,
        )
    }
}

@HiltViewModel
class ChatListViewModel @Inject constructor(messages: MessageRepository, sync: SyncEngine) : ViewModel() {
    val rows: StateFlow<List<ChatRow>?> = messages.observeSummaries()
        .map { chatRows(it, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val connection: StateFlow<ConnectionState> = sync.connection
}
