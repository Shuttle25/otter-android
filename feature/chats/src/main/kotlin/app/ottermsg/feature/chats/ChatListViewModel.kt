package app.ottermsg.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.model.ConversationSummary
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.network.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class ChatRow(
    val id: Long, val title: String,
    /** Stable id for the avatar colour (peer user id). */
    val seed: ByteArray,
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
            id = s.id, title = s.title, seed = s.peerUserId,
            preview = if (s.lastKind == MessageKind.SYSTEM_UNDECRYPTABLE) "" else s.lastBody,
            previewIsSystem = s.lastKind == MessageKind.SYSTEM_UNDECRYPTABLE,
            outgoing = s.lastOutgoing == true,
            status = if (s.lastOutgoing == true) s.lastStatus else null,
            time = if (t.toLocalDate() == today) hm.format(t) else dm.format(t),
            unread = s.unread,
        )
    }
}

sealed interface ChatListState {
    data object Loading : ChatListState
    data object Empty : ChatListState
    data class Content(val rows: List<ChatRow>) : ChatListState
}

/**
 * The no-chats art must never flash while loading: an empty list counts as truly empty only after the first
 * directory sync of this session, or when we can't sync now (offline / waiting to retry).
 */
fun chatListState(rows: List<ChatRow>?, directorySynced: Boolean, connection: ConnectionState): ChatListState = when {
    rows == null -> ChatListState.Loading
    rows.isNotEmpty() -> ChatListState.Content(rows)
    directorySynced || connection is ConnectionState.Waiting || connection is ConnectionState.Offline -> ChatListState.Empty
    else -> ChatListState.Loading
}

@HiltViewModel
class ChatListViewModel @Inject constructor(messages: MessageRepository, sync: SyncEngine, directory: DirectorySync) : ViewModel() {
    val connection: StateFlow<ConnectionState> = sync.connection
    private val rows = messages.observeSummaries()
        .map<List<ConversationSummary>, List<ChatRow>?> { chatRows(it, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault()) }
        .onStart { emit(null) }
    val state: StateFlow<ChatListState> = combine(rows, directory.synced, connection, ::chatListState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListState.Loading)
}
