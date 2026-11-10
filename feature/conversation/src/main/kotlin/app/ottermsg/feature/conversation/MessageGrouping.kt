package app.ottermsg.feature.conversation

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Ready-to-render message (no DB/Signal access in composables, guide §13). */
data class MessageUi(
    val localId: Long, val outgoing: Boolean, val text: String, val sentMs: Long,
    val status: Int, val system: Boolean, val authorName: String,
)

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
        a.outgoing != b.outgoing || a.system || b.system || date(a) != date(b) || b.sentMs - a.sentMs > GROUP_GAP_MS
    val out = ArrayList<ConversationItem>(list.size + 4)
    list.forEachIndexed { i, m ->
        val prev = list.getOrNull(i - 1)
        val next = list.getOrNull(i + 1)
        if (prev == null || date(prev) != date(m)) out += ConversationItem.DateChip(date(m))
        out += ConversationItem.Bubble(m, firstInGroup = prev == null || breaks(prev, m), lastInGroup = next == null || breaks(m, next))
    }
    return out
}
