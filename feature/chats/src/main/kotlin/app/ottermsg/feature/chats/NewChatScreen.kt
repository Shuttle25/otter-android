package app.ottermsg.feature.chats

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
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
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun NewChatRoute(onBack: () -> Unit, onOpen: (Long) -> Unit, onInvite: () -> Unit, vm: NewChatViewModel = hiltViewModel()) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.newchat_title), onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { OtterSettingsRow(stringResource(R.string.newchat_invite), icon = Icons.Outlined.PersonAdd, onClick = onInvite) }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            val list = contacts.orEmpty()
            if (contacts != null && list.isEmpty()) item {
                Text(stringResource(R.string.newchat_empty), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.screenGutter))
            }
            items(list, key = { it.userId.contentHashCode() }) { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin).clickable { vm.open(c.userId, onOpen) }
                        .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OtterAvatar(c.displayName)
                    Spacer(Modifier.width(Dimens.s12))
                    Column {
                        Text(c.displayName, style = MaterialTheme.typography.titleMedium)
                        if (c.username.isNotEmpty()) Text("@" + c.username, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
