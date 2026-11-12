package app.ottermsg.notify

import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind

fun ByteArray.toHexKey(): String = joinToString("") { "%02x".format(it) }

/** What one conversation's notification shows (D62); pure, unit-tested. */
data class NotificationContent(val title: String, val group: Boolean, val lines: List<Line>, val news: Boolean = false) {
    data class Line(val sender: String, val text: String, val timeMs: Long)

    companion object {
        const val MAX_LINES = 6

        /**
         * The last [MAX_LINES] incoming texts/photos/news (photo → «Фото», a caption appended); [names] by sender id hex.
         * News lines (D64) are from [newsSender] («Выдра» / "Otter"); their text is the stored body (phone's language).
         */
        fun build(title: String, group: Boolean, messages: List<MessageEntity>, names: Map<String, String>, photoLabel: String,
                  newsSender: String? = null) =
            NotificationContent(title, group, messages.filter { !it.outgoing && it.kind in SHOWN }
                .sortedBy { it.sentMs }.takeLast(MAX_LINES).map { m ->
                    val text = if (m.kind == MessageKind.PHOTO) (if (m.body.isBlank()) photoLabel else "$photoLabel: ${m.body}") else m.body
                    val sender = if (m.kind == MessageKind.NEWS && newsSender != null) newsSender else names[m.senderId.toHexKey()] ?: title
                    Line(sender, text, m.sentMs)
                }, news = newsSender != null)

        private val SHOWN = setOf(MessageKind.TEXT, MessageKind.PHOTO, MessageKind.NEWS)
    }
}
