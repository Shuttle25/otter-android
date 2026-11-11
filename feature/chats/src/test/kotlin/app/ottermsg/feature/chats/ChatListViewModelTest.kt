package app.ottermsg.feature.chats

import app.ottermsg.core.database.model.ConversationSummary
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ChatListViewModelTest {
    private val zone = ZoneId.of("Etc/GMT-3")
    private val now = LocalDateTime.of(2026, 11, 10, 18, 30).atZone(zone).toInstant().toEpochMilli()
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun summary(id: Long, ms: Long, body: String?, outgoing: Boolean?, status: Int?, kind: Int? = MessageKind.TEXT, unread: Int = 0) =
        ConversationSummary(id, "Чат $id", ByteArray(16), unread, ms, body, status, outgoing, kind)

    @Test fun mapsRows() {
        val rows = chatRows(listOf(
            summary(1, at(2026, 11, 10, 9, 5), "Привет", outgoing = true, status = MessageStatus.READ),
            summary(2, at(2026, 11, 9, 23, 59), "вчера", outgoing = false, status = MessageStatus.DELIVERED, unread = 3),
            summary(3, at(2026, 11, 10, 8, 0), "", outgoing = false, status = MessageStatus.DELIVERED, kind = MessageKind.SYSTEM_UNDECRYPTABLE),
            summary(4, at(2026, 11, 10, 7, 0), null, outgoing = null, status = null, kind = null),
        ), now, zone, Locale.forLanguageTag("ru"))

        assertEquals("09:05", rows[0].time)
        assertEquals(MessageStatus.READ, rows[0].status)
        assertTrue(rows[0].outgoing)

        assertEquals("9 нояб.", rows[1].time)
        assertNull(rows[1].status) // incoming rows never show a delivery icon
        assertEquals(3, rows[1].unread)

        assertTrue(rows[2].previewIsSystem)
        assertNull(rows[3].preview) // empty conversation
    }

    @Test fun englishDates() {
        val rows = chatRows(listOf(summary(1, at(2026, 1, 2, 3, 4), "x", false, null)), now, zone, Locale.ENGLISH)
        assertEquals("2 Jan", rows[0].time)
    }

    @Test fun emptyStateWaitsForFirstSync() {
        val connecting = app.ottermsg.core.network.ConnectionState.Connecting
        val waiting = app.ottermsg.core.network.ConnectionState.Waiting(0, "x")
        assertEquals(ChatListState.Loading, chatListState(null, directorySynced = true, connection = connecting))
        assertEquals(ChatListState.Loading, chatListState(emptyList(), directorySynced = false, connection = connecting))
        assertEquals(ChatListState.Empty, chatListState(emptyList(), directorySynced = true, connection = connecting))
        assertEquals(ChatListState.Empty, chatListState(emptyList(), directorySynced = false, connection = waiting))
        val row = chatRows(listOf(summary(1, now, "x", outgoing = true, status = MessageStatus.SENT)), now, zone, Locale.ROOT)
        assertEquals(ChatListState.Content(row), chatListState(row, directorySynced = false, connection = connecting))
    }
}
