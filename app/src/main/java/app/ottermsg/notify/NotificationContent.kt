package app.ottermsg.notify

import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind

fun ByteArray.toHexKey(): String = joinToString("") { "%02x".format(it) }

/** What one conversation's notification shows (D62); pure, unit-tested. */
data class NotificationContent(val title: String, val group: Boolean, val lines: List<Line>) {
    data class Line(val sender: String, val text: String, val timeMs: Long)

    companion object {
        const val MAX_LINES = 6

        /** The last [MAX_LINES] incoming texts/photos (photo → «Фото», a caption appended); [names] by sender id hex. */
        fun build(title: String, group: Boolean, messages: List<MessageEntity>, names: Map<String, String>, photoLabel: String) =
            NotificationContent(title, group, messages.filter { !it.outgoing && (it.kind == MessageKind.TEXT || it.kind == MessageKind.PHOTO) }
                .sortedBy { it.sentMs }.takeLast(MAX_LINES).map { m ->
                    val text = if (m.kind == MessageKind.PHOTO) (if (m.body.isBlank()) photoLabel else "$photoLabel: ${m.body}") else m.body
                    Line(names[m.senderId.toHexKey()] ?: title, text, m.sentMs)
                })
    }
}
