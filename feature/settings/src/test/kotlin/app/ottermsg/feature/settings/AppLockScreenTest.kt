package app.ottermsg.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.data.security.PinResult
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D121 screens: PIN setup, the PIN check with its backoff messages, and the Безопасность rows. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class AppLockScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun type(pin: String) {
        pin.forEach { compose.onNodeWithText(it.toString()).performClick() }
        compose.onNodeWithText("OK").performClick()
    }

    @Test fun enableAsksThePinTwice() {
        val enabled = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { OtterTheme {
            AppLockSetupContent(AppLockUi(enabled = false), onEnable = { p, b -> enabled += p to b },
                onBiometric = {}, onTimeout = {}, check = { PinResult.Ok }, onChangePin = { _, _ -> }, onDisable = {})
        } }
        compose.onNodeWithText("Звонки можно принять без PIN", substring = true).assertExists()
        compose.onNodeWithText("Включить").performClick()
        compose.onNodeWithText("Придумайте PIN").assertExists()
        compose.onNodeWithText("OK").assertIsNotEnabled() // under 4 digits
        type("1234")
        compose.onNodeWithText("Повторите PIN").assertExists()
        type("1235")
        compose.onNodeWithText("PIN-коды не совпадают").assertExists()
        assertEquals(emptyList<Pair<String, Boolean>>(), enabled)
        type("1234"); type("1234")
        assertEquals(listOf("1234" to false), enabled)
    }

    /** Owner default: no phone screen lock → a warning first (a forgotten PIN means reinstall + restore); not required. */
    @Test fun enablingWithoutAPhoneScreenLockWarnsFirst() {
        val enabled = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { OtterTheme {
            AppLockSetupContent(AppLockUi(enabled = false, deviceSecure = false), onEnable = { p, b -> enabled += p to b },
                onBiometric = {}, onTimeout = {}, check = { PinResult.Ok }, onChangePin = { _, _ -> }, onDisable = {})
        } }
        val warning = "На телефоне не включена блокировка экрана. Если забудете PIN, Otter придётся переустановить и восстановить из резервной копии."
        compose.onNodeWithText("Включить").performClick()
        compose.onNodeWithText(warning).assertExists()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNodeWithText("Придумайте PIN").assertDoesNotExist()
        compose.onNodeWithText("Включить").performClick()
        compose.onNodeWithText("Всё равно включить").performClick()
        compose.onNodeWithText("Придумайте PIN").assertExists()
        type("1234"); type("1234")
        assertEquals(listOf("1234" to false), enabled)
    }

    @Test fun enablingWithAPhoneScreenLockHasNoWarning() {
        compose.setContent { OtterTheme {
            AppLockSetupContent(AppLockUi(enabled = false, deviceSecure = true), onEnable = { _, _ -> },
                onBiometric = {}, onTimeout = {}, check = { PinResult.Ok }, onChangePin = { _, _ -> }, onDisable = {})
        } }
        compose.onNodeWithText("Включить").performClick()
        compose.onNodeWithText("Всё равно включить").assertDoesNotExist()
        compose.onNodeWithText("Придумайте PIN").assertExists()
    }

    @Test fun padIsAccessible() {
        compose.setContent { OtterTheme {
            AppLockSetupContent(AppLockUi(enabled = false), onEnable = { _, _ -> }, onBiometric = {}, onTimeout = {},
                check = { PinResult.Ok }, onChangePin = { _, _ -> }, onDisable = {})
        } }
        compose.onNodeWithText("Включить").performClick()
        compose.onNodeWithText("1").performClick(); compose.onNodeWithText("2").performClick()
        compose.onNode(hasContentDescription("Введено 2 из 8")).assertExists()
        compose.onNodeWithContentDescription("Стереть").performClick()
        compose.onNode(hasContentDescription("Введено 1 из 8")).assertExists()
    }

    @Test fun onStateRowsAndDisableAsksThePin() {
        var disabled = 0
        var timeout = 0L
        compose.setContent { OtterTheme {
            AppLockSetupContent(AppLockUi(enabled = true, biometricAvailable = false), onEnable = { _, _ -> },
                onBiometric = {}, onTimeout = { timeout = it }, check = { if (String(it) == "1234") PinResult.Ok else PinResult.Wrong(3) },
                onChangePin = { _, _ -> }, onDisable = { disabled++ })
        } }
        compose.onNodeWithText("Разблокировка отпечатком").assertDoesNotExist() // no strong biometric on this phone
        compose.onNodeWithText("Через 5 минут").performClick()
        assertEquals(5 * 60_000L, timeout)
        compose.onNodeWithText("Сменить PIN").assertExists()
        compose.onNodeWithText("Выключить").performClick()
        type("0000")
        compose.onNodeWithText("Неверный PIN. Осталось попыток: 3").assertExists()
        assertEquals(0, disabled)
        type("1234")
        assertEquals(1, disabled)
    }

    @Test fun biometricRowOnlyWhenAvailable() {
        val bio = mutableListOf<Boolean>()
        compose.setContent { OtterTheme {
            AppLockSetupContent(AppLockUi(enabled = true, biometricAvailable = true), onEnable = { _, _ -> },
                onBiometric = { bio += it }, onTimeout = {}, check = { PinResult.Ok }, onChangePin = { _, _ -> }, onDisable = {})
        } }
        compose.onNodeWithText("Разблокировка отпечатком").performClick()
        assertEquals(listOf(true), bio)
    }

    @Test fun changePinChecksTheOldOneThenAsksTheNewOneTwice() {
        val changes = mutableListOf<Pair<String, String>>()
        compose.setContent { OtterTheme {
            AppLockSetupContent(AppLockUi(enabled = true), onEnable = { _, _ -> }, onBiometric = {}, onTimeout = {},
                check = { if (String(it) == "1234") PinResult.Ok else PinResult.Wrong(4) },
                onChangePin = { o, n -> changes += o to n }, onDisable = {})
        } }
        compose.onNodeWithText("Сменить PIN").performClick()
        type("1234")
        type("567890"); type("567891")
        compose.onNodeWithText("PIN-коды не совпадают").assertExists()
        type("567890"); type("567890")
        assertEquals(listOf("1234" to "567890"), changes)
    }

    @Test fun waitMessage() {
        assertEquals("Повторите через 1:05", pinMessageText(PinResult.Wait(65_000), now = 0, wrong = { "x$it" }, wait = { "Повторите через $it" }))
        assertEquals("0:30", waitText(29_001))
        assertEquals("15:00", waitText(15 * 60_000L))
        assertEquals("0:00", waitText(-5))
    }

    @Test fun securityRows() {
        var lockClicks = 0
        var noShots by mutableStateOf(false)
        compose.setContent { OtterTheme { Column { SecurityRows(appLockOn = true, onAppLock = { lockClicks++ }, noScreenshots = noShots, onNoScreenshots = { noShots = it }) } } }
        compose.onNodeWithText("Включена").assertExists()
        compose.onNodeWithText("Блокировка приложения").performClick()
        assertEquals(1, lockClicks)
        compose.onNodeWithText("Экран Otter не попадёт на снимки", substring = true).assertExists()
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.onNode(isToggleable()).assertIsOn()
        assertEquals(true, noShots)
    }
}
