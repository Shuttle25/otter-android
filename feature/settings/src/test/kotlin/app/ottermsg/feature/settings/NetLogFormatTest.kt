package app.ottermsg.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.database.entity.NetLogEntity
import app.ottermsg.core.ui.theme.OtterTheme
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class NetLogFormatTest {
    @get:Rule val compose = createComposeRule()
    private val utc = ZoneId.of("UTC")

    @Test fun formatsMergedMobileFailure() {
        val e = NetLogEntity(1, "ru-1", "wss://1.2.3.4:27443", "mobile", "MTS", "timeout", "connect timed out", 0, 3,
            1_700_000_000_000, 1_700_000_600_000)
        assertEquals("14.11 22:13–22:23  ru-1  wss://1.2.3.4:27443  моб. MTS  timeout  ×3 (connect timed out)",
            formatLine(e, utc, "моб."))
    }

    @Test fun formatsSingleWifiSuccessWithoutDetail() {
        val e = NetLogEntity(2, "eeee", "wss://eeee.example:27443", "wifi", "", "ok", "ignored", 80, 1,
            1_700_000_000_000, 1_700_000_000_000)
        assertEquals("14.11 22:13  eeee  wss://eeee.example:27443  Wi-Fi  ok  ×1", formatLine(e, utc, "моб."))
    }

    @Test fun shareTextStartsWithVersionAndDate() {
        val text = shareText("Otter 0.7.0 (abc1234)", java.time.LocalDate.of(2026, 11, 13), listOf("a", "b"))
        assertEquals("Otter 0.7.0 (abc1234) · 2026-11-13\na\nb", text)
    }

    @Test fun emptyStateAndShare() {
        compose.setContent { OtterTheme { NetLogScreen(emptyList(), onBack = {}, onShare = {}) } }
        compose.onNodeWithText("Пока пусто").assertIsDisplayed()
        compose.onNodeWithContentDescription("Поделиться").assertIsNotEnabled()
    }

    @Test fun listsLinesAndShares() {
        var shared = false
        compose.setContent { OtterTheme { NetLogScreen(listOf("line one", "line two"), onBack = {}, onShare = { shared = true }) } }
        compose.onNodeWithText("Журнал подключений").assertIsDisplayed()
        compose.onNodeWithText("line two").assertIsDisplayed()
        compose.onNodeWithContentDescription("Поделиться").performClick()
        assertTrue(shared)
    }
}
