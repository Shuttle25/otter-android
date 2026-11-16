package app.ottermsg.feature.chats

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.database.model.ConversationSummary
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.Locale

/** D111: the chat list previews a re-key as «Анна: ключ безопасности изменился». */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class ChatRowKeyChangedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyChangedPreview() {
        val s = ConversationSummary(1, "Анна", ByteArray(16), 0, 1_800_000_000_000, "", MessageStatus.DELIVERED, false,
            MessageKind.SYSTEM_KEY_CHANGED)
        val row = chatRows(listOf(s), 1_800_000_000_000, ZoneId.of("Etc/GMT-3"), Locale.forLanguageTag("ru")).single()
        assertEquals("Анна", row.keyChangedBy)
        assertEquals("", row.preview)
        compose.setContent { OtterTheme { ChatRowItem(row, ByteArray(0), Names.Empty) {} } }
        compose.onNodeWithText("Анна: ключ безопасности изменился", useUnmergedTree = true).assertExists()
    }

    /** In a group the line names the re-keyed person (the sender, the new account), not the group. */
    @Test fun keyChangedPreviewInAGroup() {
        val bob = ByteArray(16) { 7 }
        val s = ConversationSummary(2, "FamG", null, 0, 1_800_000_000_000, "", MessageStatus.DELIVERED, false,
            MessageKind.SYSTEM_KEY_CHANGED, groupId = ByteArray(16) { 9 }, lastSenderId = bob)
        val names = Names(mapOf(bob.joinToString("") { "%02x".format(it) } to "Боб"))
        val row = chatRows(listOf(s), 1_800_000_000_000, ZoneId.of("Etc/GMT-3"), Locale.forLanguageTag("ru"), names).single()
        assertEquals("Боб", row.keyChangedBy)
        compose.setContent { OtterTheme { ChatRowItem(row, ByteArray(0), names) {} } }
        compose.onNodeWithText("Боб: ключ безопасности изменился", useUnmergedTree = true).assertExists()
    }
}
