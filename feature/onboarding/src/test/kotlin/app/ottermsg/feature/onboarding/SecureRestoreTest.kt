package app.ottermsg.feature.onboarding

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.ottermsg.core.ui.theme.OtterTheme
import app.ottermsg.feature.onboarding.screens.RestoreHistoryScreen
import app.ottermsg.feature.onboarding.screens.RestorePasswordScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C22 / D98: the screens that take the backup password cannot be captured, and the flag goes with them. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class SecureRestoreTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun secure() = compose.activity.window.attributes.flags and FLAG_SECURE

    @Test fun restoreByPasswordIsSecure() {
        val shown = mutableStateOf(true)
        compose.setContent { OtterTheme { if (shown.value) RestorePasswordScreen(OnboardingUiState(), {}, {}, {}, {}) } }
        compose.waitForIdle(); assertNotEquals(0, secure())
        shown.value = false
        compose.waitForIdle(); assertEquals(0, secure())
    }

    @Test fun restoreHistoryIsSecure() {
        val shown = mutableStateOf(true)
        compose.setContent { OtterTheme { if (shown.value) RestoreHistoryScreen(OnboardingUiState(), {}, {}, {}) } }
        compose.waitForIdle(); assertNotEquals(0, secure())
        shown.value = false
        compose.waitForIdle(); assertEquals(0, secure())
    }
}
