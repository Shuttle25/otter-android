package app.ottermsg.feature.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** D111: a re-keyed contact's chat shows «Анна: ключ безопасности изменился» with the new account's name. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class KeyChangedLineTest {
    @get:Rule val compose = createComposeRule()
    private fun id(n: Int) = ByteArray(16) { n.toByte() }
    private val me = id(1); private val anna = id(3)
    private val t0 = 1_800_000_000_000L
    private val conv = ConversationEntity(5, anna, "Анна", t0)
    private val contacts = listOf(ContactEntity(anna, "anna", "Анна", ByteArray(32), ByteArray(33), false, true, false, 1))
    private val msgs = listOf(
        MessageEntity(1, id(11), 5, id(2), false, t0, t0, "привет", MessageStatus.DELIVERED, MessageKind.TEXT),
        MessageEntity(2, id(12), 5, anna, false, t0 + 1000, t0, "", MessageStatus.DELIVERED, MessageKind.SYSTEM_KEY_CHANGED),
    )

    @Test fun mappingAndRendering() {
        val ui = conversationUi(conv, msgs, null, contacts, me, ZoneId.of("Etc/GMT-3"), emptyMap())
        val bubbles = ui.items.filterIsInstance<ConversationItem.Bubble>()
        assertEquals("Анна", bubbles[1].message.keyChangedName)
        assertTrue(bubbles[1].message.isEvent)
        assertEquals(1L, ui.lastIncomingId) // the line is not a message to answer
        compose.setContent { OtterTheme { ConversationScreen(ui, "", false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Анна: ключ безопасности изменился").assertExists()
    }
}
