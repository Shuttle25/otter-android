package app.ottermsg.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.feature.onboarding.screens.InviteScreen
import app.ottermsg.feature.onboarding.screens.PhraseConfirmScreen
import app.ottermsg.feature.onboarding.screens.PhraseScreen
import app.ottermsg.feature.onboarding.screens.ProfileScreen
import app.ottermsg.feature.onboarding.screens.RegisteringScreen
import app.ottermsg.feature.onboarding.screens.WelcomeScreen

/** Entry point of the join flow; calls [onDone] once the account exists. */
@Composable
fun OnboardingRoute(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step != OnboardingStep.WELCOME && state.step != OnboardingStep.DONE) { vm.back() }
    LaunchedEffect(state.step) { if (state.step == OnboardingStep.DONE) onDone() }
    when (state.step) {
        OnboardingStep.WELCOME -> WelcomeScreen(onJoin = vm::start)
        OnboardingStep.INVITE -> InviteScreen(state, vm::onLink, vm::submitLink, vm::back)
        OnboardingStep.PROFILE -> ProfileScreen(state, vm::onDisplayName, vm::onUsername, vm::submitProfile, vm::back)
        OnboardingStep.PHRASE -> PhraseScreen(state.words, vm::phraseWritten, vm::back)
        OnboardingStep.CONFIRM -> PhraseConfirmScreen(state, vm::onConfirmInput, vm::submitConfirm, vm::back)
        OnboardingStep.REGISTERING, OnboardingStep.DONE -> RegisteringScreen(state, vm::register, vm::back)
    }
}
