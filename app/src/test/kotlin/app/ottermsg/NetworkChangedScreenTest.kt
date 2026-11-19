package app.ottermsg

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D139: the warning for a manifest of another network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ru") // plain Application: OtterApp opens the real graph
class NetworkChangedScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTheWarningAndDismisses() {
        var dismissed = 0
        compose.setContent { OtterTheme { NetworkChangedScreen(onDismiss = { dismissed++ }) } }
        compose.onNodeWithText("Сеть изменилась").assertExists()
        compose.onNodeWithText("Не вводите пароли и обратитесь к администратору. Этот узел выдаёт себя за другую сеть.").assertExists()
        compose.onNodeWithText("Понятно").performClick()
        assertEquals(1, dismissed)
    }
}
