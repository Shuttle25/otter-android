package app.ottermsg.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun NewGroupRoute(onBack: () -> Unit, onCreated: (Long) -> Unit, vm: NewGroupViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    NewGroupScreen(ui, onBack, vm::onName, vm::onFamily, vm::onQuery, vm::toggle) { vm.create(onCreated) }
}

@Composable
fun NewGroupScreen(
    ui: NewGroupUi, onBack: () -> Unit, onName: (String) -> Unit, onFamily: (Boolean) -> Unit,
    onQuery: (String) -> Unit, onToggle: (ByteArray) -> Unit, onCreate: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.newgroup_title), onBack = onBack) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Dimens.screenGutter)) {
                Button(onClick = onCreate, enabled = ui.canCreate, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)) {
                    Text(stringResource(R.string.newgroup_create))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(ui.name, onName, label = { Text(stringResource(R.string.newgroup_name)) }, singleLine = true,
                    supportingText = { Text("${ui.name.length} / ${NewGroupUi.MAX_NAME}") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
            }
            item {
                // Whole row is one switch target with one accessibility state (guide §10).
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin)
                        .toggleable(ui.family, role = Role.Switch, onValueChange = onFamily)
                        .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.newgroup_family), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.newgroup_family_hint), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(Dimens.s12))
                    Switch(ui.family, onCheckedChange = null)
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                Text(stringResource(R.string.newgroup_members, ui.selectedCount), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Dimens.s16))
                OutlinedTextField(ui.query, onQuery, label = { Text(stringResource(R.string.newgroup_search)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
            }
            items(ui.contacts, key = { it.id.contentHashCode() }) { c -> PickRow(c, onToggle) }
        }
    }
}

/** Checkbox row (56 dp): the whole row toggles; TalkBack reads name and state once. */
@Composable
fun PickRow(c: PickContact, onToggle: (ByteArray) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin)
            .toggleable(c.selected, role = Role.Checkbox) { onToggle(c.id) }
            .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OtterAvatar(c.name, c.id, size = 40.dp)
        Spacer(Modifier.width(Dimens.s12))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium)
            if (c.username.isNotEmpty()) Text("@" + c.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(c.selected, onCheckedChange = null)
    }
}
