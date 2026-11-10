package app.ottermsg.feature.onboarding.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

/** Common layout: top bar, scrollable content, primary action pinned above the keyboard/navigation bar. */
@Composable
internal fun OnboardingScaffold(
    title: String,
    onBack: (() -> Unit)?,
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(title = title, onBack = onBack) },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Dimens.screenGutter),
                verticalArrangement = Arrangement.spacedBy(Dimens.s8),
                content = bottom,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Dimens.s16),
            content = content,
        )
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)) { Text(text) }
}

@Composable
internal fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}
