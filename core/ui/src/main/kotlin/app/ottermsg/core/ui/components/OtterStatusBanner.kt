package app.ottermsg.core.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.ottermsg.core.ui.theme.Dimens

/** Calm one-line status strip under the top bar (guide §8: offline / connecting). */
@Composable
fun OtterStatusBanner(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
    }
}
