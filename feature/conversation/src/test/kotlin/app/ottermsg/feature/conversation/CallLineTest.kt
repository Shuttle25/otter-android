package app.ottermsg.feature.conversation

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.ui.callLineText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CallLineTest {
    private val res: Resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test @Config(qualifiers = "ru") fun russianLines() {
        assertEquals("Исходящий звонок · 3:12", callLineText(res, 1, 192))
        assertEquals("Входящий звонок · 0:45", callLineText(res, 2, 45))
        assertEquals("Пропущенный звонок", callLineText(res, 3, 0))
        assertEquals("Отклонён", callLineText(res, 4, 0))
        assertEquals("Не отвечает", callLineText(res, 5, 0))
        assertEquals("Занято", callLineText(res, 6, 0))
        assertEquals("Не удалось соединиться", callLineText(res, 7, 0))
        assertEquals("Исходящий звонок · 61:05", callLineText(res, 1, 3665)) // minutes are not wrapped into hours
    }

    @Test @Config(qualifiers = "en") fun englishLines() {
        assertEquals("Outgoing call · 3:12", callLineText(res, 1, 192))
        assertEquals("Incoming call · 0:45", callLineText(res, 2, 45))
        assertEquals("Missed call", callLineText(res, 3, 0))
        assertEquals("Declined", callLineText(res, 4, 0))
        assertEquals("No answer", callLineText(res, 5, 0))
        assertEquals("Busy", callLineText(res, 6, 0))
        assertEquals("Couldn’t connect", callLineText(res, 7, 0))
    }

    @Test fun callMessageMapsToACallLineEvent() {
        val conv = ConversationEntity(id = 1, peerUserId = byteArrayOf(2), title = "Анна", lastActivityMs = 1)
        val msgs = listOf(
            MessageEntity(localId = 1, messageId = byteArrayOf(1), conversationId = 1, senderId = byteArrayOf(2), outgoing = false,
                sentMs = 1_000, receivedMs = 1_000, body = "привет", status = MessageStatus.DELIVERED),
            MessageEntity(localId = 2, messageId = byteArrayOf(3), conversationId = 1, senderId = byteArrayOf(2), outgoing = true,
                sentMs = 2_000, receivedMs = 2_000, body = "", status = MessageStatus.READ, kind = MessageKind.CALL,
                readReceiptSent = true, callOutcome = 1, callDurationS = 192),
        )
        val ui = conversationUi(conv, msgs, null, emptyList(), byteArrayOf(9), ZoneId.of("UTC"))
        val bubbles = ui.items.filterIsInstance<ConversationItem.Bubble>()
        assertNull(bubbles[0].message.call)
        val call = bubbles[1].message
        assertEquals(CallLineUi(1, 192), call.call)
        assertTrue(call.isEvent)
        assertTrue(bubbles[1].firstInGroup && bubbles[1].lastInGroup && bubbles[0].lastInGroup)
    }
}
