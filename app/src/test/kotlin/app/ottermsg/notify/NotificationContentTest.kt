package app.ottermsg.notify

import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationContentTest {
    private fun msg(sender: Int, body: String, kind: Int = MessageKind.TEXT, t: Long, outgoing: Boolean = false) = MessageEntity(localId = t,
        messageId = ByteArray(16), conversationId = 7, senderId = ByteArray(16) { sender.toByte() }, outgoing = outgoing,
        sentMs = t, receivedMs = t, body = body, status = MessageStatus.DELIVERED, kind = kind)

    @Test fun directChat() {
        val c = NotificationContent.build(title = "Мама", group = false,
            messages = listOf(msg(1, "Купи хлеб", t = 1), msg(1, "", MessageKind.PHOTO, 2), msg(1, "дача", MessageKind.PHOTO, 3)),
            names = mapOf(ByteArray(16) { 1 }.toHexKey() to "Мама"), photoLabel = "Фото")
        assertEquals("Мама", c.title)
        assertEquals(listOf("Мама" to "Купи хлеб", "Мама" to "Фото", "Мама" to "Фото: дача"), c.lines.map { it.sender to it.text })
    }

    @Test fun keepsLastSix() {
        val c = NotificationContent.build("Семья", true, (1..9).map { msg(2, "m$it", t = it.toLong()) },
            mapOf(ByteArray(16) { 2 }.toHexKey() to "Папа"), "Фото")
        assertEquals((4..9).map { "m$it" }, c.lines.map { it.text })
        assertEquals("Папа", c.lines.first().sender)
    }

    @Test fun skipsOutgoingAndSystemAndSortsByTime() {
        val c = NotificationContent.build("Семья", true, listOf(msg(2, "b", t = 5), msg(3, "a", t = 4),
            msg(2, "", MessageKind.SYSTEM_GROUP, 6), msg(9, "мой", t = 7, outgoing = true)), emptyMap(), "Фото")
        assertEquals(listOf("Семья" to "a", "Семья" to "b"), c.lines.map { it.sender to it.text })
    }
}
