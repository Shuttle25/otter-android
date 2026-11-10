package app.ottermsg.feature.onboarding.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.feature.onboarding.R

@Composable
fun WelcomeScreen(onJoin: () -> Unit) {
    OnboardingScaffold(title = "", onBack = null, bottom = { PrimaryButton(stringResource(R.string.onb_welcome_join), onJoin) }) {
        Spacer(Modifier.height(Dimens.s32))
        Text(stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        BodyText(stringResource(R.string.onb_welcome_text))
        Text(stringResource(R.string.onb_welcome_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
