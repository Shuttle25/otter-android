package app.ottermsg.feature.onboarding

enum class OnboardingStep { WELCOME, INVITE, PROFILE, PHRASE, CONFIRM, REGISTERING, DONE }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val link: String = "", val linkError: Int? = null, val inviterName: String? = null,
    val displayName: String = "", val username: String = "", val profileError: Int? = null,
    val words: List<String> = emptyList(), val confirmPositions: List<Int> = emptyList(),
    val confirmInputs: List<String> = listOf("", "", ""), val confirmError: Boolean = false,
    val registering: Boolean = false, val registrationError: Int? = null,
)
