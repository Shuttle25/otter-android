package app.ottermsg.feature.onboarding

enum class OnboardingStep {
    WELCOME, INVITE, PROFILE, PHRASE, CONFIRM, REGISTERING, DONE,
    RESTORE_LINK, RESTORE_METHOD, RESTORE_PASSWORD, RESTORE_WORDS, RESTORING, RESTORE_HISTORY,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val link: String = "", val linkError: Int? = null, val inviterName: String? = null,
    val displayName: String = "", val username: String = "", val profileError: Int? = null,
    val words: List<String> = emptyList(), val confirmPositions: List<Int> = emptyList(),
    val confirmInputs: List<String> = listOf("", "", ""), val confirmError: Boolean = false,
    val registering: Boolean = false, val registrationError: Int? = null,
    // restore (D54/D55)
    val restoreUsername: String = "", val restorePassword: String = "",
    val restoreWords: List<String> = List(24) { "" },
    val restoreError: Int? = null, val restoring: Boolean = false, val restoreViaWords: Boolean = false,
)
