package app.ottermsg.feature.settings

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.ottermsg.core.data.backup.BackupState
import app.ottermsg.core.ui.components.SecureWindow
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C22 / D98: FLAG_SECURE while a backup password is typed; reference-counted across overlapping screens. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class SecureScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun secure() = compose.activity.window.attributes.flags and FLAG_SECURE

    @Test fun backupPasswordFormIsSecureOnlyWhileOpen() {
        val form = mutableStateOf(BackupViewModel.Form(open = true))
        compose.setContent { OtterTheme {
            BackupScreen(BackupState.Off, true, form.value, SnackbarHostState(), {}, {}, {}, {}, {}, {})
        } }
        compose.waitForIdle(); assertNotEquals(0, secure())
        form.value = BackupViewModel.Form()
        compose.waitForIdle(); assertEquals(0, secure())
    }

    @Test fun overlappingSecureScreensKeepTheFlag() {
        val first = mutableStateOf(true); val second = mutableStateOf(true)
        compose.setContent { if (first.value) SecureWindow(); if (second.value) SecureWindow() }
        compose.waitForIdle()
        first.value = false
        compose.waitForIdle(); assertNotEquals(0, secure()) // the other one still needs it
        second.value = false
        compose.waitForIdle(); assertEquals(0, secure())
    }

    /** D121: the app-wide «Запрет скриншотов» is one more holder; leaving a secret screen keeps it, turning it off keeps the screen's. */
    @Test fun appWideSettingAndSecretScreenOverlap() {
        val appWide = mutableStateOf(true); val phrase = mutableStateOf(true)
        compose.setContent { SecureWindow(enabled = appWide.value); if (phrase.value) SecureWindow() }
        compose.waitForIdle(); assertNotEquals(0, secure())
        phrase.value = false
        compose.waitForIdle(); assertNotEquals(0, secure()) // leaving the phrase screen does not clear the setting
        phrase.value = true; appWide.value = false
        compose.waitForIdle(); assertNotEquals(0, secure()) // switching the setting off keeps the phrase screen secure
        phrase.value = false
        compose.waitForIdle(); assertEquals(0, secure())
        appWide.value = true
        compose.waitForIdle(); assertNotEquals(0, secure())
    }
}
