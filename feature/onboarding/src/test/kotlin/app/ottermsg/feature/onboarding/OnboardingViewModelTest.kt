package app.ottermsg.feature.onboarding

import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.data.backup.RestoreError
import app.ottermsg.core.data.backup.Restorer
import app.ottermsg.core.data.registration.Registrar
import app.ottermsg.core.data.registration.RegistrationError
import app.ottermsg.proto.v1.InviteBundle
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private class FakeRegistrar(var result: Result<Unit> = Result.success(Unit)) : Registrar {
        var calls = 0; var lastSeed: ByteArray? = null
        override suspend fun register(link: String, displayName: String, username: String, seed: ByteArray): Result<Unit> {
            calls++; lastSeed = seed; return result
        }
    }

    private val link = InviteLink.encode(InviteBundle.newBuilder().setSecret(ByteString.copyFrom(ByteArray(32))).setInviterName("Мама").build())
    private class FakeRestorer : Restorer {
        var password: Result<Unit> = Result.success(Unit); var words: Result<Unit> = Result.success(Unit)
        var history: Result<Unit> = Result.success(Unit)
        val calls = mutableListOf<String>()
        override suspend fun withPassword(link: String, username: String, password: String) = this.password.also { calls += "password:$username:$password" }
        override suspend fun withWords(link: String, words: List<String>) = this.words.also { calls += "words:${words.size}" }
        override suspend fun addHistory(password: String) = history.also { calls += "history:$password" }
    }

    private val reg = FakeRegistrar()
    private val rest = FakeRestorer()
    private lateinit var vm: OnboardingViewModel

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()); vm = OnboardingViewModel(reg, rest) }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun toConfirm() {
        vm.start(); vm.onLink(link); vm.submitLink()
        vm.onDisplayName("Боря"); vm.onUsername("borya"); vm.submitProfile()
        vm.phraseWritten()
    }
    private fun typeCorrectWords() { val s = vm.state.value; s.confirmPositions.forEachIndexed { i, p -> vm.onConfirmInput(i, " ${s.words[p].uppercase()} ") } }

    @Test fun invalidLinkShowsError() {
        vm.start(); vm.onLink("https://example.org/join"); vm.submitLink()
        assertEquals(R.string.onb_invite_invalid, vm.state.value.linkError)
        assertEquals(OnboardingStep.INVITE, vm.state.value.step)
        vm.onLink(link) // typing clears the error
        assertNull(vm.state.value.linkError)
        vm.submitLink()
        assertEquals(OnboardingStep.PROFILE, vm.state.value.step)
        assertEquals("Мама", vm.state.value.inviterName)
    }

    @Test fun usernameRule() {
        vm.start(); vm.onLink(link); vm.submitLink()
        vm.onDisplayName("Боря")
        vm.onUsername("Bo!")
        assertEquals("bo", vm.state.value.username) // filtered as typed
        vm.submitProfile()
        assertEquals(R.string.onb_profile_username_rule, vm.state.value.profileError)
        vm.onDisplayName("  "); vm.onUsername("borya"); vm.submitProfile()
        assertEquals(R.string.onb_profile_name_required, vm.state.value.profileError)
        vm.onDisplayName("Боря"); vm.submitProfile()
        assertEquals(OnboardingStep.PHRASE, vm.state.value.step)
    }

    @Test fun phraseHas24WordsAndThreeDistinctPositions() {
        val s = vm.state.value
        assertEquals(24, s.words.size)
        assertEquals(3, s.confirmPositions.toSet().size)
        assertEquals(s.confirmPositions.sorted(), s.confirmPositions)
    }

    @Test fun wrongWordFailsRightWordsRegister() {
        toConfirm()
        val s = vm.state.value
        vm.onConfirmInput(0, "wrongword"); vm.onConfirmInput(1, s.words[s.confirmPositions[1]]); vm.onConfirmInput(2, s.words[s.confirmPositions[2]])
        vm.submitConfirm()
        assertTrue(vm.state.value.confirmError)
        assertEquals(0, reg.calls)
        typeCorrectWords()
        vm.submitConfirm()
        assertEquals(1, reg.calls)
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
        assertEquals(32, reg.lastSeed!!.size)
    }

    @Test fun registrationErrorsMapToMessagesAndBackTargets() {
        val cases = mapOf(
            RegistrationError.UsernameTaken to (R.string.onb_err_username_taken to OnboardingStep.PROFILE),
            RegistrationError.InviteUsed to (R.string.onb_err_invite_used to OnboardingStep.INVITE),
            RegistrationError.InviteExpired to (R.string.onb_err_invite_expired to OnboardingStep.INVITE),
            RegistrationError.InviteUnknown to (R.string.onb_invite_invalid to OnboardingStep.INVITE),
            RegistrationError.Network to (R.string.onb_err_network to OnboardingStep.CONFIRM),
            RegistrationError.Unknown("x") to (R.string.onb_err_unknown to OnboardingStep.CONFIRM),
        )
        for ((err, expected) in cases) {
            reg.result = Result.failure(err)
            vm = OnboardingViewModel(reg, rest)
            toConfirm(); typeCorrectWords(); vm.submitConfirm()
            assertEquals(err.toString(), expected.first, vm.state.value.registrationError)
            assertEquals(OnboardingStep.REGISTERING, vm.state.value.step)
            vm.back()
            assertEquals(err.toString(), expected.second, vm.state.value.step)
        }
    }

    @Test fun retryUsesTheSameSeed() {
        reg.result = Result.failure(RegistrationError.Network)
        toConfirm(); typeCorrectWords(); vm.submitConfirm()
        val first = reg.lastSeed!!.copyOf()
        reg.result = Result.success(Unit)
        vm.register()
        assertArrayEquals(first, reg.lastSeed)
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
    }

    private fun toRestoreMethod() { vm.startRestore(); vm.onLink(link); vm.submitRestoreLink() }

    @Test fun restoreWithPasswordErrorsStayOnForm() {
        vm.startRestore(); vm.onLink("nope"); vm.submitRestoreLink()
        assertEquals(R.string.onb_invite_invalid, vm.state.value.linkError)
        vm.onLink(link); vm.submitRestoreLink()
        assertEquals(OnboardingStep.RESTORE_METHOD, vm.state.value.step)
        vm.chooseMethod(words = false)
        vm.onRestoreUsername(" Anna "); vm.onRestorePassword("secret pw")
        rest.password = Result.failure(RestoreError.WrongPassword)
        vm.submitRestore()
        assertEquals(OnboardingStep.RESTORE_PASSWORD, vm.state.value.step)
        assertEquals(R.string.onb_restore_err_password, vm.state.value.restoreError)
        rest.password = Result.failure(RestoreError.TooManyAttempts)
        vm.submitRestore()
        assertEquals(R.string.onb_restore_err_many, vm.state.value.restoreError)
        rest.password = Result.success(Unit)
        vm.submitRestore()
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
        assertEquals("password:anna:secret pw", rest.calls.last())
    }

    @Test fun restoreWithWordsThenHistoryOrSkip() {
        toRestoreMethod(); vm.chooseMethod(words = true)
        val phrase = Bip39Words.sample.joinToString(" ")
        vm.onWord(0, "  $phrase  ") // paste into the first field fills all 24
        assertEquals(Bip39Words.sample, vm.state.value.restoreWords)
        vm.onWord(3, "WORD ")
        assertEquals("word", vm.state.value.restoreWords[3])
        vm.submitRestore()
        assertEquals(OnboardingStep.RESTORE_HISTORY, vm.state.value.step)
        vm.onRestorePassword("pw")
        rest.history = Result.failure(RestoreError.WrongPassword)
        vm.addHistory()
        assertEquals(R.string.onb_restore_err_password, vm.state.value.restoreError)
        assertEquals(OnboardingStep.RESTORE_HISTORY, vm.state.value.step)
        vm.skipHistory()
        assertEquals(OnboardingStep.DONE, vm.state.value.step)
    }

    @Test fun restoreBackNavigation() {
        toRestoreMethod(); vm.chooseMethod(words = true)
        vm.back(); assertEquals(OnboardingStep.RESTORE_METHOD, vm.state.value.step)
        vm.back(); assertEquals(OnboardingStep.RESTORE_LINK, vm.state.value.step)
        vm.back(); assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
    }
}

private object Bip39Words {
    val sample: List<String> = app.ottermsg.core.crypto.Bip39.toMnemonic(ByteArray(32) { 7 })
}
