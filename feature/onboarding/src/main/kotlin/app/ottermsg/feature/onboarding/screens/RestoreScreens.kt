package app.ottermsg.feature.onboarding.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.ui.components.SecureWindow
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.feature.onboarding.OnboardingUiState
import app.ottermsg.feature.onboarding.R

@Composable
fun RestoreMethodScreen(onChoose: (words: Boolean) -> Unit, onBack: () -> Unit) {
    OnboardingScaffold(title = stringResource(R.string.onb_restore_method_title), onBack = onBack, bottom = {}) {
        MethodCard(stringResource(R.string.onb_restore_by_password), stringResource(R.string.onb_restore_by_password_hint)) { onChoose(false) }
        MethodCard(stringResource(R.string.onb_restore_by_words), stringResource(R.string.onb_restore_by_words_hint)) { onChoose(true) }
    }
}

@Composable
private fun MethodCard(title: String, hint: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).clickable(role = Role.Button, onClick = onClick)) {
        Column(Modifier.padding(Dimens.s16), verticalArrangement = Arrangement.spacedBy(Dimens.s4)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RestorePasswordScreen(state: OnboardingUiState, onUsername: (String) -> Unit, onPassword: (String) -> Unit,
                          onSubmit: () -> Unit, onBack: () -> Unit) {
    SecureWindow()
    OnboardingScaffold(title = stringResource(R.string.onb_restore_by_password), onBack = onBack, bottom = {
        PrimaryButton(stringResource(R.string.onb_restore_do), onSubmit,
            enabled = state.restoreUsername.isNotBlank() && state.restorePassword.isNotEmpty())
    }) {
        BodyText(stringResource(R.string.onb_restore_by_password_hint))
        OutlinedTextField(state.restoreUsername, onUsername, singleLine = true, label = { Text(stringResource(R.string.onb_restore_username)) },
            prefix = { Text("@") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth())
        PasswordField(state.restorePassword, onPassword)
        state.restoreError?.let { ErrorBox(it) }
    }
}

@Composable
internal fun PasswordField(value: String, onValue: (String) -> Unit, enabled: Boolean = true) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(value, onValue, singleLine = true, enabled = enabled, label = { Text(stringResource(R.string.onb_restore_password)) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.onb_restore_show_password))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth())
}

/** 24 numbered fields in two columns; a whole phrase pasted into field 1 fills all of them. */
@Composable
fun RestoreWordsScreen(state: OnboardingUiState, onWord: (Int, String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    SecureWindow()
    OnboardingScaffold(title = stringResource(R.string.onb_restore_words_title), onBack = onBack, bottom = {
        PrimaryButton(stringResource(R.string.onb_restore_do), onSubmit, enabled = state.restoreWords.all { Bip39.isWord(it) })
    }) {
        BodyText(stringResource(R.string.onb_restore_words_text))
        for (row in 0 until 12) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                for (col in 0 until 2) {
                    val i = col * 12 + row
                    val w = state.restoreWords[i]
                    OutlinedTextField(w, { onWord(i, it) }, singleLine = true,
                        label = { Text("${i + 1}") },
                        isError = w.isNotEmpty() && !Bip39.isWord(w),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password, imeAction = if (i < 23) ImeAction.Next else ImeAction.Done),
                        modifier = Modifier.weight(1f))
                }
            }
        }
        state.restoreError?.let { ErrorBox(it) }
    }
}

@Composable
fun RestoringScreen() {
    OnboardingScaffold(title = stringResource(R.string.onb_restore_title), onBack = null, bottom = {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s16),
            modifier = Modifier.padding(top = Dimens.s24).semantics { liveRegion = LiveRegionMode.Polite }) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.onb_restore_searching), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun RestoreHistoryScreen(state: OnboardingUiState, onPassword: (String) -> Unit, onRestore: () -> Unit, onSkip: () -> Unit) {
    SecureWindow()
    OnboardingScaffold(title = stringResource(R.string.onb_restore_history_title), onBack = null, bottom = {
        PrimaryButton(stringResource(R.string.onb_restore_history_do), onRestore, enabled = !state.restoring && state.restorePassword.isNotEmpty())
        SecondaryButton(stringResource(R.string.onb_restore_history_skip), onSkip, enabled = !state.restoring)
    }) {
        BodyText(stringResource(R.string.onb_restore_history_text))
        PasswordField(state.restorePassword, onPassword, enabled = !state.restoring)
        if (state.restoring) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s16)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.onb_restore_searching), style = MaterialTheme.typography.bodyLarge)
        }
        state.restoreError?.let { ErrorBox(it) }
    }
}

@Composable
private fun ErrorBox(text: Int) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
        Text(stringResource(text), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(Dimens.s16))
    }
}
