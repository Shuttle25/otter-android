package app.ottermsg.feature.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Код аккаунта» (D111): what the person reads aloud to the admin for `otter-admin user rekey --new CODE`. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class AccountCodeTest {
    @get:Rule val compose = createComposeRule()
    private val id = ByteArray(16) { (it * 17 + 0xa0).toByte() }

    @Test fun eightGroupsOfFourHexDigits() {
        val code = accountCode(id)
        assertEquals("a0b1 c2d3 e4f5 0617 2839 4a5b 6c7d 8e9f", code)
        // the admin CLI (ParseUserCode) drops spaces and dashes and wants 32 hex digits = the user id
        val digits = code.replace(" ", "")
        assertTrue(digits.matches(Regex("[0-9a-f]{32}")))
        assertEquals(id.toList(), digits.chunked(2).map { it.toInt(16).toByte() })
    }

    @Test fun profileShowsAndCopiesTheCode() {
        compose.setContent { OtterTheme { ProfileScreen(ProfileInfo(id, "anna", "Анна"), false, {}, {}) } }
        compose.onNodeWithText("Код аккаунта").assertExists()
        compose.onNodeWithText("Нужен администратору, если телефон потерян").assertExists()
        compose.onNodeWithText("a0b1 c2d3 e4f5 0617 2839 4a5b 6c7d 8e9f").assertExists().performTouchInput { longClick() }
        compose.waitForIdle()
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals("a0b1 c2d3 e4f5 0617 2839 4a5b 6c7d 8e9f", cm.primaryClip!!.getItemAt(0).text.toString())
    }
}
