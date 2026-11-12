package app.ottermsg.feature.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.BackupState
import app.ottermsg.core.ui.theme.OtterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeBackups : BackupControl {
    override val state = MutableStateFlow<BackupState>(BackupState.Off)
    override val online = MutableStateFlow(true)
    val passwords = mutableListOf<String>()
    var runResult = BackupManager.OK
    override suspend fun setPassword(password: String): Int { passwords += password; state.value = BackupState.On(1000, false, 0); return BackupManager.OK }
    override suspend fun runNow(): Int = runResult
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class BackupViewModelTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun weakPasswordKeepsSubmitDisabled() = runTest(dispatcher) {
        val fake = FakeBackups()
        val vm = BackupViewModel(fake)
        vm.openForm(); vm.onPw("password12"); vm.onRepeat("password12")
        assertFalse(BackupViewModel.canSubmit(vm.form.value))
        vm.submit(); advanceUntilIdle()
        assertEquals(emptyList<String>(), fake.passwords)
        vm.onPw("корова лодка зима семь"); vm.onRepeat("корова лодка зима")
        assertFalse(BackupViewModel.canSubmit(vm.form.value)) // mismatch
    }

    @Test fun strongMatchingPasswordIsSetOnce() = runTest(dispatcher) {
        val fake = FakeBackups()
        val vm = BackupViewModel(fake)
        val messages = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.message.collect { messages += it } }
        vm.openForm(); vm.onPw("корова лодка зима семь"); vm.onRepeat("корова лодка зима семь")
        vm.submit(); vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("корова лодка зима семь"), fake.passwords)
        assertFalse(vm.form.value.open)
        assertEquals(listOf(R.string.backup_done), messages)
        fake.runResult = BackupManager.ERR_OFFLINE
        vm.runNow(); advanceUntilIdle()
        assertEquals(R.string.backup_offline, messages.last())
        assertEquals(BackupState.On(1000, false, 0), vm.state.first { it != null })
    }

    @Test fun screenStates() {
        var form by mutableStateOfForm(BackupViewModel.Form(open = true, pw = "корова лодка зима семь", repeat = "корова лодка зима семь"))
        compose.setContent {
            OtterTheme { BackupScreen(BackupState.Off, true, form, SnackbarHostState(), {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Включить").assertIsEnabled()
        compose.onNodeWithContentDescription("Показать пароль").assertExists()
        compose.onNodeWithContentDescription("Надёжность пароля", substring = true).assertExists()
        form = BackupViewModel.Form(open = true, pw = "корова", repeat = "корова")
        compose.onNodeWithText("Включить").assertIsNotEnabled()
    }
}

private fun mutableStateOfForm(f: BackupViewModel.Form) = androidx.compose.runtime.mutableStateOf(f)
