package app.ottermsg.feature.conversation

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class MessageBubbleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outgoingReadBubbleIsOneAccessibleUnit() {
        val m = MessageUi(1, outgoing = true, text = "Привет", sentMs = 0, status = DeliveryStatus.READ, system = false, authorName = "")
        compose.setContent { OtterTheme { MessageBubble(m, firstInGroup = true) } }
        compose.onNode(SemanticsMatcher("description has text and status") { node ->
            val d = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
            "Вы" in d && "Привет" in d && "Прочитано" in d
        }).assertExists()
    }

    @Test fun undecryptableShowsSystemText() {
        val m = MessageUi(2, outgoing = false, text = "", sentMs = 0, status = DeliveryStatus.DELIVERED, system = true, authorName = "Мама")
        compose.setContent { OtterTheme { MessageBubble(m, firstInGroup = true) } }
        compose.onNode(SemanticsMatcher("system text") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty().contains("Не удалось прочитать сообщение")
        }).assertExists()
    }

    @Test fun sendDisabledForBlank() {
        compose.setContent { OtterTheme { MessageComposer(text = "  ", sending = false, onText = {}, onSend = {}) } }
        compose.onNodeWithContentDescription("Отправить сообщение").assertIsNotEnabled()
    }

    @Test fun sendDisabledWhileSending() {
        compose.setContent { OtterTheme { MessageComposer(text = "hi", sending = true, onText = {}, onSend = {}) } }
        compose.onNodeWithContentDescription("Отправить сообщение").assertIsNotEnabled()
    }

    @Test fun sendEnabledWithText() {
        compose.setContent { OtterTheme { MessageComposer(text = "hi", sending = false, onText = {}, onSend = {}) } }
        compose.onNodeWithContentDescription("Отправить сообщение").assertIsEnabled()
    }
}
