package app.ottermsg.feature.onboarding.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.feature.onboarding.OnboardingUiState
import app.ottermsg.feature.onboarding.R

@Composable
fun RegisteringScreen(state: OnboardingUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    val error = state.registrationError
    OnboardingScaffold(
        title = stringResource(R.string.onb_registering).trimEnd('…'), onBack = if (state.registering) null else onBack,
        bottom = {
            if (!state.registering && error != null) {
                PrimaryButton(stringResource(R.string.onb_retry), onRetry)
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_back)) }
            }
        },
    ) {
        if (state.registering || error == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.s16),
                modifier = Modifier.padding(top = Dimens.s24)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.onb_registering), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
                Column(Modifier.padding(Dimens.s16)) {
                    Text(stringResource(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
