package app.ottermsg.feature.onboarding.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.ottermsg.feature.onboarding.OnboardingUiState
import app.ottermsg.feature.onboarding.R

@Composable
fun ProfileScreen(state: OnboardingUiState, onName: (String) -> Unit, onUsername: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    val nameError = state.profileError == R.string.onb_profile_name_required
    val usernameError = state.profileError != null && !nameError
    OnboardingScaffold(
        title = stringResource(R.string.onb_profile_title), onBack = onBack,
        bottom = { PrimaryButton(stringResource(R.string.onb_invite_continue), onSubmit) },
    ) {
        state.inviterName?.let { Text(stringResource(R.string.onb_invite_from, it), style = MaterialTheme.typography.bodyLarge) }
        OutlinedTextField(
            value = state.displayName, onValueChange = onName, singleLine = true,
            label = { Text(stringResource(R.string.onb_profile_name)) },
            isError = nameError, supportingText = if (nameError) { { Text(stringResource(R.string.onb_profile_name_required)) } } else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.username, onValueChange = onUsername, singleLine = true,
            label = { Text(stringResource(R.string.onb_profile_username)) },
            isError = usernameError,
            supportingText = { Text(stringResource(if (usernameError) state.profileError!! else R.string.onb_profile_username_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
