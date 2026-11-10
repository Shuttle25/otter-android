package app.ottermsg.feature.onboarding.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.feature.onboarding.R

@Composable
fun PhraseScreen(words: List<String>, onDone: () -> Unit, onBack: () -> Unit) {
    SecureWindow()
    OnboardingScaffold(
        title = stringResource(R.string.onb_phrase_title), onBack = onBack,
        bottom = { PrimaryButton(stringResource(R.string.onb_phrase_done), onDone) },
    ) {
        BodyText(stringResource(R.string.onb_phrase_text))
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxWidth().padding(Dimens.s16),
                verticalArrangement = Arrangement.spacedBy(Dimens.s8),
            ) {
                for (row in words.indices.chunked(2)) {
                    Row(Modifier.fillMaxWidth()) {
                        for (i in row) Text("${i + 1}. ${words[i]}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** FLAG_SECURE while the phrase is visible: no screenshots, hidden in the recents preview. */
@Composable
internal fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
