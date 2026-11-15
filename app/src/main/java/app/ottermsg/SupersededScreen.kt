package app.ottermsg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.components.OtterIllustration
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.core.ui.R as UiR

/** Shown instead of the app when another phone signed a newer install of this account (D52, C14). */
@Composable
fun SupersededScreen(onReclaim: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.screenGutter),
        verticalArrangement = Arrangement.spacedBy(Dimens.s16, Alignment.CenterVertically)) {
        OtterIllustration(UiR.drawable.otter_welcome, maxSize = 160.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.superseded_title), style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.superseded_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.superseded_reclaim))
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.superseded_reclaim_title)) },
        text = { Text(stringResource(R.string.superseded_reclaim_text)) },
        confirmButton = { TextButton(onClick = { confirm = false; onReclaim() }) { Text(stringResource(R.string.superseded_reclaim_yes)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.superseded_reclaim_no)) } },
    )
}
