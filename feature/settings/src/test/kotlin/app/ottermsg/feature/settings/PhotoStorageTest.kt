package app.ottermsg.feature.settings

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.ottermsg.core.data.settings.Settings
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

private class FakePrefs : PhotoPrefs {
    override val autoDownload = MutableStateFlow(Settings.AUTO_WIFI)
    override val saveToGallery = MutableStateFlow(false)
    var cleared = 0
    override suspend fun setAutoDownload(v: Int) { autoDownload.value = v }
    override suspend fun setSaveToGallery(v: Boolean) { saveToGallery.value = v }
    override fun galleryNeedsPermission() = false
    override suspend fun sizes() = (if (cleared > 0) 0L else 5000L) to 123_000L
    override suspend fun clearCache() { cleared++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class PhotoStorageTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewModelTogglesAndClears() {
        val d = StandardTestDispatcher()
        Dispatchers.setMain(d)
        try {
            runTest(d) {
                val prefs = FakePrefs()
                val vm = PhotoStorageViewModel(prefs)
                advanceUntilIdle()
                assertEquals(Settings.AUTO_WIFI, vm.autoDownload.value)
                assertEquals(5000L to 123_000L, vm.sizes.value)
                vm.setAuto(Settings.AUTO_MANUAL); vm.setSave(true); advanceUntilIdle()
                assertEquals(Settings.AUTO_MANUAL, vm.autoDownload.value)
                assertEquals(true, vm.saveToGallery.value)
                vm.clearCache(); advanceUntilIdle()
                assertEquals(1, prefs.cleared)
                assertEquals(0L, vm.sizes.value.first)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun screenShowsLabelsAndSelection() {
        var auto = -1; var cleared = false
        compose.setContent {
            OtterTheme { PhotoStorageScreen(Settings.AUTO_WIFI, false, 0L to 0L, {}, { auto = it }, {}, { cleared = true }) }
        }
        compose.onNodeWithText("Автозагрузка фото").assertExists()
        compose.onNodeWithText("Только по Wi-Fi").assertExists()
        compose.onNodeWithText("Фото в чатах не удаляются").assertExists()
        compose.onNodeWithText("Фото на телефоне").assertExists()
        compose.onNodeWithText("Сохранять в галерею").assertExists()
        compose.onNodeWithText("Вручную").performClick()
        assertEquals(Settings.AUTO_MANUAL, auto)
        compose.onNodeWithText("Очистить").performClick()
        assertEquals(true, cleared)
    }
}
