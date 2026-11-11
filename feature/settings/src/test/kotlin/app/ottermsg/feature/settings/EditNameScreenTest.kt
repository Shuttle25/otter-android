package app.ottermsg.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class EditNameScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun validatesAndSaves() {
        val store = FakeProfileStore("Мама")
        var back = false
        compose.setContent { OtterTheme { EditNameRoute(onBack = { back = true }, vm = EditNameViewModel(store)) } }
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()   // unchanged
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("Введите имя").assertExists()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Оля")
        compose.onNodeWithText("3 / 64").assertExists()
        compose.onNodeWithText("Сохранить").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { back }
        assertEquals("Оля", store.saved)
        assertTrue(back)
    }

    @Test fun profileShowsPendingNote() {
        compose.setContent {
            OtterTheme { ProfileScreen(ProfileInfo(ByteArray(16), "mama", "Мама"), pending = true, onBack = {}, onEditName = {}) }
        }
        compose.onNodeWithText("Имя обновится у близких, когда появится подключение").assertExists()
        compose.onNodeWithText("@mama · нельзя изменить").assertExists()
    }
}
