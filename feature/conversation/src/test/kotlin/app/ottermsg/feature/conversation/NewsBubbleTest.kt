package app.ottermsg.feature.conversation

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.ui.theme.OtterTheme
import app.ottermsg.proto.v1.Announcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class NewsBubbleTest {
    @get:Rule val compose = createComposeRule()

    private fun links(t: String) = findLinks(t).map { t.substring(it) }

    @Test fun detectsHttpLinks() {
        assertEquals(listOf("https://a.b/c?d=1"), links("Смотрите https://a.b/c?d=1 тут"))
        assertEquals(listOf("http://x"), links("http://x"))
        assertEquals(emptyList<String>(), links("ftp://x"))
        assertEquals(listOf("https://a.b/c"), links("Ссылка: https://a.b/c."))
        assertEquals(listOf("https://a.b"), links("(см. https://a.b)"))
        assertEquals(listOf("HTTPS://A.B", "http://c.d/e"), links("HTTPS://A.B, http://c.d/e!"))
    }

    @Test fun newsConversationIsReadOnlyAndCarriesAnnouncement() {
        val a = Announcement.newBuilder().setTextRu("Привет").setTextEn("Hello").build()
        val conv = ConversationEntity(3, null, "", 0, type = ConversationType.NEWS)
        val msg = MessageEntity(messageId = ByteArray(32), conversationId = 3, senderId = ByteArray(16), outgoing = false, sentMs = 1,
            receivedMs = 1, body = "Привет", status = MessageStatus.DELIVERED, kind = MessageKind.NEWS, systemData = a.toByteArray())
        val ui = conversationUi(conv, listOf(msg), null, emptyList(), ByteArray(16), ZoneId.of("UTC"))
        assertTrue(ui.isNews)
        assertFalse(ui.canWrite)
        assertFalse(ui.isGroup)
        val m = (ui.items.last() as ConversationItem.Bubble).message
        assertEquals(a, m.news)
        assertEquals("Hello", newsText(m, "en"))
        assertEquals("Привет", newsText(m, "ru"))
    }

    @Test fun linkIsAButtonAndAsksBeforeOpening() {
        val opened = mutableListOf<String>()
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val m = MessageUi(1, outgoing = false, text = "Новое: https://ottermsg.app/x.", sentMs = 0, status = MessageStatus.DELIVERED,
            system = false, authorName = "Выдра")
        compose.setContent {
            OtterTheme { CompositionLocalProvider(LocalUriHandler provides handler) { NewsBubble(m, m.text, firstInGroup = true) } }
        }
        compose.onNodeWithContentDescription("Ссылка: https://ottermsg.app/x")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Открыть ссылку?").assertExists()
        compose.onNodeWithText("https://ottermsg.app/x").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Открыть ссылку?").assertDoesNotExist()
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithContentDescription("Ссылка: https://ottermsg.app/x").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Открыть").performClick()
        assertEquals(listOf("https://ottermsg.app/x"), opened)
    }
}
