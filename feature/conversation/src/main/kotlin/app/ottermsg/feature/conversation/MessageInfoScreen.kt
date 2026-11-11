package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun MessageInfoRoute(onBack: () -> Unit, vm: MessageInfoViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    MessageInfoScreen(ui, onBack)
}

/** "Доставлено X из N", "Прочитано Y из N", then each recipient with its state and time (guide §8, D23). */
@Composable
fun MessageInfoScreen(ui: MessageInfoUi, onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.info_title), onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.fillMaxWidth().padding(Dimens.screenGutter), verticalArrangement = Arrangement.spacedBy(Dimens.s4)) {
                    Text(ui.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
                    Spacer(Modifier.padding(top = Dimens.s4))
                    Text(stringResource(R.string.info_delivered, ui.delivered, ui.total), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.info_read, ui.read, ui.total), style = MaterialTheme.typography.titleMedium)
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            items(ui.rows) { r ->
                val state = when (r.state) {
                    RecipientState.PENDING -> stringResource(R.string.info_state_pending)
                    RecipientState.DELIVERED -> stringResource(R.string.info_state_delivered, timeOf(r.timeMs ?: 0))
                    RecipientState.READ -> stringResource(R.string.info_state_read, timeOf(r.timeMs ?: 0))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin).padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8)
                    .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        if (!r.stillInGroup) Text(stringResource(R.string.info_not_in_group), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(Dimens.s12))
                    Text(state, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
