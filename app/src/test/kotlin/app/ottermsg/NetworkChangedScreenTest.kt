package app.ottermsg

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** D139: the warning for a manifest of another network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ru") // plain Application: OtterApp opens the real graph
class NetworkChangedScreenTest {
    val compose = createComposeRule()

    /** The app module gets ui-test-manifest's host activity only in debug; the release unit tests register it here. */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val app = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }).around(compose)

    @Test fun showsTheWarningAndDismisses() {
        var dismissed = 0
        compose.setContent { OtterTheme { NetworkChangedScreen(onDismiss = { dismissed++ }) } }
        compose.onNodeWithText("Сеть изменилась").assertExists()
        compose.onNodeWithText("Не вводите пароли и обратитесь к администратору. Этот узел выдаёт себя за другую сеть.").assertExists()
        compose.onNodeWithText("Понятно").performClick()
        assertEquals(1, dismissed)
    }
}
