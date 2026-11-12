package app.ottermsg.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.data.push.PushMode
import app.ottermsg.core.ui.theme.OtterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class NotificationsViewModelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mapsModeAndSwitch() {
        val d = StandardTestDispatcher()
        Dispatchers.setMain(d)
        try {
            runTest(d) {
                val mode = MutableStateFlow(PushMode.FAST)
                val enabled = MutableStateFlow(true)
                var saved: Boolean? = null
                val vm = NotificationsViewModel(mode, enabled) { saved = it }
                assertEquals(NotificationsUi(PushMode.FAST, true), vm.ui.first { it != null })
                mode.value = PushMode.SAVER
                assertEquals(PushMode.SAVER, vm.ui.first { it?.mode == PushMode.SAVER }!!.mode)
                vm.setEnabled(false)
                advanceUntilIdle()
                assertEquals(false, saved)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun saverWordingAndBlockedWarning() {
        var toggled: Boolean? = null
        compose.setContent {
            OtterTheme { NotificationsScreen(NotificationsUi(PushMode.SAVER, true), systemOn = false, {}, { toggled = it }, {}) }
        }
        compose.onNodeWithText("Экономия батареи").assertExists()
        compose.onNodeWithText("Проверяем каждые 15 минут: сервисы Google недоступны или не отвечают").assertExists()
        compose.onNodeWithText("Уведомления запрещены в настройках Android").assertExists()
        compose.onNodeWithText("Настройки Android").assertExists()
        compose.onNodeWithText("Показывать уведомления").performClick()
        compose.waitForIdle()
        assertEquals(false, toggled)
    }

    @Test fun fastWordingNoWarning() {
        compose.setContent {
            OtterTheme { NotificationsScreen(NotificationsUi(PushMode.FAST, true), systemOn = true, {}, {}, {}) }
        }
        compose.onNodeWithText("Быстрые уведомления").assertExists()
        compose.onNodeWithText("Приходят через Google, почти сразу").assertExists()
        compose.onNodeWithText("Уведомления запрещены в настройках Android").assertDoesNotExist()
    }
}
