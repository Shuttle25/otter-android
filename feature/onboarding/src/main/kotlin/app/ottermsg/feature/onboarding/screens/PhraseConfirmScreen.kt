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
fun PhraseConfirmScreen(state: OnboardingUiState, onInput: (Int, String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    SecureWindow()
    OnboardingScaffold(
        title = stringResource(R.string.onb_confirm_title), onBack = onBack,
        bottom = { PrimaryButton(stringResource(R.string.onb_confirm_continue), onSubmit, enabled = state.confirmInputs.all { it.isNotBlank() }) },
    ) {
        BodyText(stringResource(R.string.onb_confirm_text))
        state.confirmPositions.forEachIndexed { i, pos ->
            OutlinedTextField(
                value = state.confirmInputs[i], onValueChange = { onInput(i, it) }, singleLine = true,
                label = { Text(stringResource(R.string.onb_confirm_word, pos + 1)) },
                isError = state.confirmError,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password, imeAction = if (i < 2) ImeAction.Next else ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.confirmError) Text(stringResource(R.string.onb_confirm_wrong), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
    }
}
