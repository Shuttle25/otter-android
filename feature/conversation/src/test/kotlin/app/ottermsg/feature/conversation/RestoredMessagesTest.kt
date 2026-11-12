package app.ottermsg.feature.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.AttachmentState
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

/** M6: a reset chip with the contact's name; restored photos show «Нет в резервной копии». */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class RestoredMessagesTest {
    @get:Rule val compose = createComposeRule()
    private fun id(n: Int) = ByteArray(16) { n.toByte() }
    private val me = id(1); private val boris = id(2)
    private val t0 = 1_800_000_000_000L
    private val conv = ConversationEntity(5, boris, "Борис", t0)
    private val contacts = listOf(ContactEntity(boris, "boris", "Борис", ByteArray(32), ByteArray(33), false, false, false, 1))
    private val msgs = listOf(
        MessageEntity(1, id(11), 5, boris, false, t0, t0, "кот", MessageStatus.DELIVERED, MessageKind.PHOTO),
        MessageEntity(2, id(12), 5, boris, false, t0 + 1000, t0, "", MessageStatus.DELIVERED, MessageKind.SYSTEM_RESET),
    )
    private val att = AttachmentEntity(messageLocalId = 1, blobKey = ByteArray(32), key = ByteArray(32), digest = ByteArray(32), size = 100,
        mime = "image/jpeg", width = 640, height = 480, thumb = null, file = null, state = AttachmentState.NOT_IN_BACKUP)

    @Test fun mappingAndRendering() {
        val ui = conversationUi(conv, msgs, null, contacts, me, ZoneId.of("Etc/GMT-3"), mapOf(1L to att))
        val bubbles = ui.items.filterIsInstance<ConversationItem.Bubble>()
        assertEquals(PhotoState.NotInBackup, bubbles[0].message.photo!!.state)
        assertEquals("Борис", bubbles[1].message.resetName)
        assertTrue(bubbles[1].message.isEvent)
        compose.setContent { OtterTheme { ConversationScreen(ui, "", false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Борис восстановил(а) аккаунт").assertExists()
        compose.onNodeWithText("Нет в резервной копии", useUnmergedTree = true).assertExists()
    }
}
