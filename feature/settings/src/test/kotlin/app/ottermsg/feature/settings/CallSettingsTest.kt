package app.ottermsg.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.ui.theme.OtterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
class CallSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun relayOnlyDefaultsOnAndTogglingSavesIt() {
        val d = StandardTestDispatcher()
        Dispatchers.setMain(d)
        try {
            runTest(d) {
                val stored = MutableStateFlow(true)
                val saved = mutableListOf<Boolean>()
                val vm = SettingsViewModel(MutableStateFlow(null), MutableStateFlow(ConnectionState.Connecting), stored,
                    { saved += it; stored.value = it })
                advanceUntilIdle()
                assertEquals(true, vm.callsRelayOnly.value)
                vm.setCallsRelayOnly(false); advanceUntilIdle()
                assertEquals(listOf(false), saved)
                assertEquals(false, vm.callsRelayOnly.value)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun relayOnlyIsOnBeforeTheStoreAnswers() {
        val d = StandardTestDispatcher()
        Dispatchers.setMain(d)
        try {
            val vm = SettingsViewModel(MutableStateFlow(null), MutableStateFlow(ConnectionState.Connecting), emptyFlow(), {})
            assertEquals(true, vm.callsRelayOnly.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun switchRowTogglesAndFsiRowShowsOnlyWhenBlocked() {
        var on by mutableStateOf(true)
        var fixes = 0
        compose.setContent { OtterTheme { Column { CallSettingsRows(on, { on = it }, fsiBlocked = true, onFsiFix = { fixes++ }) } } }
        compose.onNodeWithText("Собеседник не увидит ваш IP-адрес").assertExists()
        compose.onNodeWithText("Звонки только через сервер").assertExists()
        compose.onNodeWithText("Разрешить звонки на заблокированном экране").performClick()
        assertEquals(1, fixes)
        compose.onNode(isToggleable()).assertIsOn().performClick()
        assertEquals(false, on)
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun fsiRowHiddenWhenAllowed() {
        compose.setContent { OtterTheme { CallSettingsRows(true, {}, fsiBlocked = false, onFsiFix = {}) } }
        compose.onNodeWithText("Разрешить звонки на заблокированном экране").assertDoesNotExist()
    }
}
