package app.ottermsg.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.ui.theme.OtterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/** D124 «Оформление»: three radio rows; a choice is saved as the ThemeMode ordinal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class AppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choosingDarkSavesTwo() {
        val saved = mutableListOf<Int>()
        var mode by mutableIntStateOf(0)
        compose.setContent { OtterTheme { AppearanceContent(mode, onSelect = { saved += it; mode = it }) } }
        compose.onNodeWithText("Как на устройстве").assertIsSelected()
        compose.onNodeWithText("Светлая").assertIsNotSelected()
        compose.onNodeWithText("Тёмная").performClick()
        assertEquals(listOf(2), saved)
        compose.onNodeWithText("Тёмная").assertIsSelected()
        compose.onNodeWithText("Как на устройстве").assertIsNotSelected()
        compose.onNodeWithText("Светлая").performClick()
        assertEquals(listOf(2, 1), saved)
    }

    @Test fun viewModelReadsAndSavesTheStoredMode() {
        val d = StandardTestDispatcher()
        Dispatchers.setMain(d)
        try {
            runTest(d) {
                val stored = MutableStateFlow(1)
                val vm = AppearanceViewModel(stored) { stored.value = it }
                advanceUntilIdle()
                assertEquals(1, vm.mode.value)
                vm.set(2); advanceUntilIdle()
                assertEquals(2, stored.value)
                assertEquals(2, vm.mode.value)
            }
        } finally { Dispatchers.resetMain() }
    }
}
