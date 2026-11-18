package app.ottermsg.feature.settings

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.data.security.PinResult
import app.ottermsg.core.ui.theme.OtterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D124 «Фраза восстановления»: 24 words in two numbered columns under FLAG_SECURE, only after the screen lock. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class RecoveryPhraseTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun secure() = compose.activity.window.attributes.flags and FLAG_SECURE
    private val words = List(24) { "w$it" }

    @Test fun wordsAreNumberedWithTheWarningAndSecure() {
        compose.setContent { OtterTheme {
            RecoveryPhraseContent(words, canAuthenticate = true, pinPad = false, check = { PinResult.Ok }, onPinOk = {}, onAuthenticate = {})
        } }
        compose.onNodeWithText("1. w0").assertExists()
        compose.onNodeWithText("12. w11").assertExists()
        compose.onNodeWithText("13. w12").assertExists()
        compose.onNodeWithText("24. w23").assertExists()
        compose.onNodeWithText("Никому не показывайте эти слова. Кто их знает — тот владеет аккаунтом.").assertExists()
        compose.waitForIdle(); assertNotEquals(0, secure())
    }

    @Test fun withoutAnyLockOnlyTheExplanation() {
        compose.setContent { OtterTheme {
            RecoveryPhraseContent(null, canAuthenticate = false, pinPad = false, check = { PinResult.Ok }, onPinOk = {}, onAuthenticate = {})
        } }
        compose.onNodeWithText("Сначала включите блокировку экрана телефона или блокировку Otter").assertExists()
        compose.onNodeWithText("1. ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Показать слова").assertDoesNotExist()
    }

    @Test fun beforeTheScreenLockNoWordsAndAButtonToAsk() {
        var asked = 0
        compose.setContent { OtterTheme {
            RecoveryPhraseContent(null, canAuthenticate = true, pinPad = false, check = { PinResult.Ok }, onPinOk = {}, onAuthenticate = { asked++ })
        } }
        compose.onNodeWithText("1. ", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Показать слова").performClick()
        assertEquals(1, asked)
    }

    /** No phone screen lock but the Otter lock is on: the app-lock PIN pad (with its backoff) stands in. */
    @Test fun pinPadWhenThePhoneHasNoScreenLock() {
        var ok = 0
        compose.setContent { OtterTheme {
            RecoveryPhraseContent(null, canAuthenticate = true, pinPad = true,
                check = { if (String(it) == "1234") PinResult.Ok else PinResult.Wrong(4) }, onPinOk = { ok++ }, onAuthenticate = {})
        } }
        fun type(pin: String) { pin.forEach { compose.onNodeWithText(it.toString()).performClick() }; compose.onNodeWithText("OK").performClick() }
        type("0000"); compose.waitForIdle()
        assertEquals(0, ok)
        compose.onNodeWithText("Неверный PIN", substring = true).assertExists()
        type("1234"); compose.waitForIdle()
        assertEquals(1, ok)
    }

    /** The words come from the account seed via Bip39, and only when asked (after the prompt), never at construction. */
    @Test fun viewModelDerivesWordsOnlyOnRequest() {
        val seed = ByteArray(32) { it.toByte() }
        var reads = 0
        val vm = RecoveryPhraseViewModel({ reads++; seed }, MutableStateFlow(false)) { PinResult.Ok }
        assertEquals(0, reads)
        assertEquals(Bip39.toMnemonic(seed), vm.words())
        assertEquals(1, reads)
    }
}
