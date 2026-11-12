package app.ottermsg.feature.conversation

import app.ottermsg.proto.v1.GroupEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Ready-to-render message (no DB/Signal access in composables, guide §13). */
data class MessageUi(
    val localId: Long, val outgoing: Boolean, val text: String, val sentMs: Long,
    val status: Int, val system: Boolean, val authorName: String,
    /** Stable author key (user id hex); a change starts a new series. */
    val authorKey: String = if (outgoing) "me" else authorName,
    /** Group system event (rendered as a centered chip). */
    val event: GroupEvent? = null,
    /** Incoming message in a group: the author's name is shown at the start of a series. */
    val inGroup: Boolean = false,
    /** Photo message (text = caption). */
    val photo: PhotoUi? = null,
    /** SYSTEM_RESET: this contact restored their account (D53); rendered as a centered chip with their name. */
    val resetName: String? = null,
    /** kind = NEWS: the signed announcement (text picked in the phone's language at display time, D69). */
    val news: app.ottermsg.proto.v1.Announcement? = null,
) {
    val isEvent: Boolean get() = event != null || resetName != null
}

/** What a photo bubble shows (guide §8–§9). */
enum class PhotoState { Uploading, Ready, Remote, Downloading, Failed, Gone, NotInBackup }

data class PhotoUi(val width: Int, val height: Int, val state: PhotoState, val progress: Float? = null)

sealed interface ConversationItem {
    val key: String
    data class DateChip(val date: LocalDate) : ConversationItem { override val key = "d$date" }
    data class Bubble(val message: MessageUi, val firstInGroup: Boolean, val lastInGroup: Boolean) : ConversationItem {
        override val key = "m${message.localId}"
    }
}

const val GROUP_GAP_MS = 5 * 60 * 1000L

/**
 * Oldest-first list with date chips. A new series starts on author change, a system message, a date
 * change or a pause of more than five minutes (guide §7).
 */
fun groupMessages(list: List<MessageUi>, zone: ZoneId): List<ConversationItem> {
    fun date(m: MessageUi) = LocalDate.ofInstant(Instant.ofEpochMilli(m.sentMs), zone)
    fun breaks(a: MessageUi, b: MessageUi) =
        a.outgoing != b.outgoing || a.authorKey != b.authorKey || a.system || b.system || a.isEvent || b.isEvent ||
            date(a) != date(b) || b.sentMs - a.sentMs > GROUP_GAP_MS
    val out = ArrayList<ConversationItem>(list.size + 4)
    list.forEachIndexed { i, m ->
        val prev = list.getOrNull(i - 1)
        val next = list.getOrNull(i + 1)
        if (prev == null || date(prev) != date(m)) out += ConversationItem.DateChip(date(m))
        out += ConversationItem.Bubble(m, firstInGroup = prev == null || breaks(prev, m), lastInGroup = next == null || breaks(m, next))
    }
    return out
}
