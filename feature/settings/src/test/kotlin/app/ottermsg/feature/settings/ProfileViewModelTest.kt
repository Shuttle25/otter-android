package app.ottermsg.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun editStateValidatesAndSaves() = runTest(dispatcher) {
        val store = FakeProfileStore(name = "Мама")
        val vm = EditNameViewModel(store)
        assertEquals("Мама", vm.state.value.text)
        assertFalse(vm.state.value.canSave)               // unchanged
        vm.onText("   ")
        assertEquals(EditNameError.Empty, vm.state.value.error)
        vm.onText("я".repeat(65))
        assertEquals(EditNameError.TooLong, vm.state.value.error)
        vm.onText("😀".repeat(64))                        // 64 code points: allowed
        assertNull(vm.state.value.error)
        assertEquals("64 / 64", vm.state.value.counter)
        vm.onText(" Мама ")                                // only whitespace changed: nothing to save
        assertFalse(vm.state.value.canSave)
        vm.onText("Мама Оля")
        assertTrue(vm.state.value.canSave)
        assertEquals("8 / 64", vm.state.value.counter)
        var done = false
        vm.save { done = true }
        assertFalse("saving blocks a second save", vm.state.value.canSave)
        advanceUntilIdle()
        assertTrue(done)
        assertEquals("Мама Оля", store.saved)
    }

    @Test fun profileExposesPending() {
        val store = FakeProfileStore(name = "Мама")
        val vm = ProfileViewModel(store)
        assertFalse(vm.pending.value)
        store.pending.value = true
        assertTrue(vm.pending.value)
        assertEquals("mama", vm.profile.value!!.username)
    }
}
