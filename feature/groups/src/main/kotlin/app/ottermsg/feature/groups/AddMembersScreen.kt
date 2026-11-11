package app.ottermsg.feature.groups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun AddMembersRoute(onBack: () -> Unit, vm: AddMembersViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.group_add_members), onBack = onBack) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Dimens.screenGutter)) {
                Button(onClick = { vm.add(onBack) }, enabled = ui.canAdd, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)) {
                    Text(stringResource(R.string.group_add_button, ui.selectedCount))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(ui.query, vm::onQuery, label = { Text(stringResource(R.string.newgroup_search)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
            }
            if (ui.contacts.isEmpty()) item {
                Text(stringResource(R.string.group_add_nobody), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.screenGutter))
            }
            items(ui.contacts, key = { it.id.contentHashCode() }) { c -> PickRow(c, vm::toggle) }
        }
    }
}
