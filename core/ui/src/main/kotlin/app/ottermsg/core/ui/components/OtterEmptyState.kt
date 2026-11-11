package app.ottermsg.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun OtterEmptyState(text: String, modifier: Modifier = Modifier) {
    OtterEmptyState(title = null, text = text, modifier = modifier)
}

/** Empty state: optional Otter illustration (hidden when space is short), title, text and one primary action. */
@Composable
fun OtterEmptyState(
    title: String?, text: String?, modifier: Modifier = Modifier,
    @DrawableRes illustration: Int? = null, illustrationMax: Dp = Dimens.s32,
    action: String? = null, onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(Dimens.s32),
        verticalArrangement = Arrangement.spacedBy(Dimens.s12, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (illustration != null) OtterIllustration(illustration, illustrationMax)
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        if (action != null) Button(onClick = onAction, modifier = Modifier.heightIn(min = Dimens.minTouch)) { Text(action) }
    }
}
