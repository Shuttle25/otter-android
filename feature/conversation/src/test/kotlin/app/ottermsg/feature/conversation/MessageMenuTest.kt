package app.ottermsg.feature.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.espresso.Espresso
import app.ottermsg.core.ui.components.AnchoredMenu
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.components.MenuAction
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class MessageMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressShowsMenuAndBackDismisses() {
        val m = MessageUi(1, outgoing = true, text = "Привет", sentMs = 0, status = DeliveryStatus.SENT, system = false, authorName = "")
        compose.setContent {
            OtterTheme {
                var open by remember { mutableStateOf(false) }
                MessageBubble(m, firstInGroup = true, onLongPress = { open = true }, menu = {
                    AnchoredMenu(open, alignEnd = true,
                        actions = listOf(MenuAction("Копировать") {}, MenuAction("Удалить у меня") {}),
                        onDismiss = { open = false })
                })
            }
        }
        compose.onNodeWithText("Копировать").assertDoesNotExist()
        compose.onNode(SemanticsMatcher("bubble") { n ->
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty().contains("Привет")
        }).performTouchInput { longClick() }
        compose.onNodeWithText("Копировать").assertExists()
        compose.onNodeWithText("Удалить у меня").assertExists()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Копировать").assertDoesNotExist()
    }
}
