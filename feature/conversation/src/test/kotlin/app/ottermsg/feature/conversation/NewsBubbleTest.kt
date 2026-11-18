package app.ottermsg.feature.conversation

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import app.ottermsg.core.ui.theme.Dimens
import androidx.compose.ui.unit.dp
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.ui.theme.OtterTheme
import app.ottermsg.proto.v1.Announcement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    @Test fun updateRowStates() {
        var state by androidx.compose.runtime.mutableStateOf<UpdateUi>(UpdateUi.Idle)
        var taps = 0
        compose.setContent { OtterTheme { UpdateRow("0.6.0", state) { taps++ } } }
        compose.onNodeWithText("Обновить до 0.6.0").performClick()
        assertEquals(1, taps)
        state = UpdateUi.Downloading(42)
        compose.onNodeWithText("Загрузка… 42 %").assertExists()
        compose.onNodeWithText("Обновить до 0.6.0").assertDoesNotExist()
        state = UpdateUi.Error(R.string.news_update_corrupt)
        compose.onNodeWithText("Файл повреждён").assertExists()
        compose.onNodeWithText("Обновить до 0.6.0").assertExists()
    }

    @Test fun noSenderLineButTalkBackKeepsIt() {
        val m = MessageUi(1, outgoing = false, text = "Otter 0.9.1: новые галочки", sentMs = 0, status = MessageStatus.DELIVERED,
            system = false, authorName = "Выдра")
        compose.setContent { OtterTheme { NewsBubble(m, m.text, firstInGroup = true) } }
        compose.onAllNodesWithText("Выдра", useUnmergedTree = true).assertCountEquals(0)
        val text = compose.onNode(SemanticsMatcher("description keeps the sender") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty().startsWith("Выдра, Otter 0.9.1")
        }, useUnmergedTree = true).assertExists().fetchSemanticsNode()
        // The old sender line had no semantics, so check the layout: the text starts right under the bubble's top padding.
        val bubble = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), useUnmergedTree = true)
            .fetchSemanticsNode()
        val gap = text.boundsInRoot.top - bubble.boundsInRoot.top
        val max = with(compose.density) { (Dimens.s8 + 1.dp).toPx() }
        assertTrue("text starts ${gap}px below the bubble top (max $max)", gap <= max)
    }
}
