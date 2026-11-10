package app.ottermsg.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.data.registration.Registrar
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.registration.RegistrationError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject
import kotlin.random.asKotlinRandom

/**
 * Join flow. The seed lives only in this ViewModel (never in SavedStateHandle): after process death the
 * flow restarts with a new phrase, which is safe because nothing was registered yet.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(private val registration: Registrar) : ViewModel() {
    private val seed = ByteArray(32).also(SecureRandom()::nextBytes)
    private val _state = MutableStateFlow(OnboardingUiState(words = Bip39.toMnemonic(seed),
        confirmPositions = (0 until 24).shuffled(SecureRandom().asKotlinRandom()).take(3).sorted()))
    val state: StateFlow<OnboardingUiState> = _state

    fun start() = update { copy(step = OnboardingStep.INVITE) }
    fun onLink(text: String) = update { copy(link = text, linkError = null) }
    fun submitLink() {
        val b = runCatching { InviteLink.decode(_state.value.link) }.getOrNull()
        update { if (b == null) copy(linkError = R.string.onb_invite_invalid) else copy(inviterName = b.inviterName.ifEmpty { null }, step = OnboardingStep.PROFILE) }
    }
    fun onDisplayName(v: String) = update { copy(displayName = v.take(64), profileError = null) }
    fun onUsername(v: String) = update { copy(username = v.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(32), profileError = null) }
    fun submitProfile() = update {
        when {
            displayName.isBlank() -> copy(profileError = R.string.onb_profile_name_required)
            !Registration.USERNAME.matches(username) -> copy(profileError = R.string.onb_profile_username_rule)
            else -> copy(step = OnboardingStep.PHRASE)
        }
    }
    fun phraseWritten() = update { copy(step = OnboardingStep.CONFIRM) }
    fun onConfirmInput(i: Int, v: String) = update { copy(confirmInputs = confirmInputs.toMutableList().also { it[i] = v.trim().lowercase() }, confirmError = false) }
    fun submitConfirm() {
        val s = _state.value
        val ok = s.confirmPositions.indices.all { s.confirmInputs[it] == s.words[s.confirmPositions[it]] }
        if (!ok) { update { copy(confirmError = true) }; return }
        register()
    }
    fun register() {
        val s = _state.value
        if (s.registering) return
        update { copy(step = OnboardingStep.REGISTERING, registering = true, registrationError = null) }
        viewModelScope.launch {
            val r = registration.register(s.link, s.displayName, s.username, seed)
            update {
                r.fold(onSuccess = { copy(registering = false, step = OnboardingStep.DONE) },
                    onFailure = { copy(registering = false, registrationError = errorMessage(it)) })
            }
        }
    }
    fun back() = update {
        when (step) {
            OnboardingStep.INVITE -> copy(step = OnboardingStep.WELCOME)
            OnboardingStep.PROFILE -> copy(step = OnboardingStep.INVITE)
            OnboardingStep.PHRASE -> copy(step = OnboardingStep.PROFILE)
            OnboardingStep.CONFIRM -> copy(step = OnboardingStep.PHRASE)
            OnboardingStep.REGISTERING -> when {
                registering -> this
                registrationError in PROFILE_ERRORS -> copy(step = OnboardingStep.PROFILE)
                registrationError == R.string.onb_invite_invalid || registrationError == R.string.onb_err_invite_used ||
                    registrationError == R.string.onb_err_invite_expired -> copy(step = OnboardingStep.INVITE)
                else -> copy(step = OnboardingStep.CONFIRM)
            }
            else -> this
        }
    }
    private inline fun update(f: OnboardingUiState.() -> OnboardingUiState) { _state.value = _state.value.f() }

    companion object {
        private val PROFILE_ERRORS = setOf(R.string.onb_err_username_taken, R.string.onb_profile_username_rule, R.string.onb_profile_name_required)

        fun errorMessage(t: Throwable): Int = when (t) {
            RegistrationError.UsernameTaken -> R.string.onb_err_username_taken
            RegistrationError.BadUsername -> R.string.onb_profile_username_rule
            RegistrationError.BadDisplayName -> R.string.onb_profile_name_required
            RegistrationError.InviteUsed -> R.string.onb_err_invite_used
            RegistrationError.InviteExpired -> R.string.onb_err_invite_expired
            RegistrationError.InviteUnknown, RegistrationError.BadLink -> R.string.onb_invite_invalid
            RegistrationError.Network -> R.string.onb_err_network
            else -> R.string.onb_err_unknown
        }
    }
}
