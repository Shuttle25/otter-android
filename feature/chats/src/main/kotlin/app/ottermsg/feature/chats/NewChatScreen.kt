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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.data.directory.UsernameLookup
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatRoute(onBack: () -> Unit, onOpen: (Long) -> Unit, onInvite: () -> Unit, onNewGroup: () -> Unit = {}, vm: NewChatViewModel = hiltViewModel()) {
    val shown by vm.shown.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val person by vm.person.collectAsStateWithLifecycle()
    NewChatScreen(shown, query, vm::onQuery, onFind = vm::find, searching = searching, failed = failed,
        onPick = { vm.open(it, onOpen) }, onBack = onBack, onInvite = onInvite, onNewGroup = onNewGroup)
    person?.let { p ->
        ModalBottomSheet(onDismissRequest = vm::dismissPerson) {
            PersonCard(p.contact.displayName, p.contact.username, p.contact.userId, p.hasChat,
                onWrite = { vm.dismissPerson(); vm.open(p.contact.userId, onOpen) })
        }
    }
}

/**
 * «Написать»: a search field over the catalog (name or `@username`, D136) and, for a valid username nobody in the list
 * has exactly, the row «Найти @name» (exact lookup, D137).
 */
@Composable
internal fun NewChatScreen(
    contacts: List<ContactEntity>?, query: String, onQuery: (String) -> Unit, onFind: () -> Unit, searching: Boolean,
    failed: UsernameLookup.Result?, onPick: (ByteArray) -> Unit, onBack: () -> Unit, onInvite: () -> Unit, onNewGroup: () -> Unit,
) {
    val offer = contacts?.let { findOffer(it, query) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.newchat_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, onQuery, singleLine = true,
                label = { Text(stringResource(R.string.newchat_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (offer != null && !searching) onFind() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
            lookupMessage(failed)?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Dimens.screenGutter).semantics { liveRegion = LiveRegionMode.Polite })
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (query.isBlank()) {
                    item { OtterSettingsRow(stringResource(R.string.newchat_group), icon = Icons.Outlined.GroupAdd, onClick = onNewGroup) }
                    item { OtterSettingsRow(stringResource(R.string.newchat_invite), icon = Icons.Outlined.PersonAdd, onClick = onInvite) }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                }
                if (offer != null) item {
                    OtterSettingsRow(stringResource(R.string.newchat_find, offer), icon = Icons.Outlined.PersonSearch,
                        onClick = { if (!searching) onFind() })
                }
                val list = contacts.orEmpty()
                if (contacts != null && list.isEmpty() && query.isBlank()) item {
                    Text(stringResource(R.string.newchat_empty), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimens.screenGutter))
                }
                items(list, key = { it.userId.contentHashCode() }) { c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin).clickable { onPick(c.userId) }
                            .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OtterAvatar(c.displayName, c.userId)
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
}
