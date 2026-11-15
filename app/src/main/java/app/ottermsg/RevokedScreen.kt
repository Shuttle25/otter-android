package app.ottermsg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.components.OtterIllustration
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.core.ui.R as UiR

/** Shown instead of the app once the phone verified that the network administrator disabled this account (C2). */
@Composable
fun RevokedScreen() {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.screenGutter),
        verticalArrangement = Arrangement.spacedBy(Dimens.s16, Alignment.CenterVertically)) {
        OtterIllustration(UiR.drawable.otter_welcome, maxSize = 160.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.revoked_title), style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.revoked_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}
