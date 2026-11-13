package app.ottermsg.feature.chats

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class ChatRowCallTest {
    @get:Rule val compose = createComposeRule()

    private fun row(outcome: Int, duration: Long) = ChatRow(1, "Анна", ByteArray(16), preview = "", previewIsSystem = false, outgoing = true,
        status = null, time = "09:00", unread = 0, callOutcome = outcome, callDurationS = duration)

    @Test fun outgoingCallPreview() {
        compose.setContent { OtterTheme { ChatRowItem(row(1, 192), ByteArray(0), Names.Empty) {} } }
        compose.onNodeWithText("Исходящий звонок · 3:12", useUnmergedTree = true).assertExists()
    }

    @Test fun missedCallPreview() {
        compose.setContent { OtterTheme { ChatRowItem(row(3, 0), ByteArray(0), Names.Empty) {} } }
        compose.onNodeWithText("Пропущенный звонок", useUnmergedTree = true).assertExists()
    }
}
