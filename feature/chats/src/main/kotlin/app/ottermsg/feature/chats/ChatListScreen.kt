package app.ottermsg.feature.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.DeliveryStatusIcon
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterEmptyState
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.components.deliveryStatusLabel
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun ChatListRoute(myName: String, onOpen: (Long) -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit, vm: ChatListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                val settingsLabel = stringResource(R.string.chats_settings)
                OtterTopBar(title = stringResource(R.string.chats_title), actions = {
                    IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = settingsLabel }) {
                        OtterAvatar(myName, Dimens.avatarTopBar - Dimens.s8)
                    }
                })
                ConnectionBanner(connection)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewChat, icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.chats_write)) })
        },
    ) { padding ->
        val list = rows
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding)) // first load is local and instant: no skeleton
            list.isEmpty() -> OtterEmptyState(stringResource(R.string.chats_empty), Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.id }) { row -> ChatRowItem(row) { onOpen(row.id) } }
            }
        }
    }
}

@Composable
private fun ChatRowItem(row: ChatRow, onClick: () -> Unit) {
    val preview = when {
        row.previewIsSystem -> stringResource(R.string.chats_undecryptable)
        row.preview == null -> ""
        row.outgoing -> stringResource(R.string.chats_you, row.preview)
        else -> row.preview
    }
    val statusLabel = row.status?.let { stringResource(deliveryStatusLabel(it)) }
    val unreadLabel = if (row.unread > 0) stringResource(R.string.chats_unread, row.unread) else null
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin).clickable(onClick = onClick)
            .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(row.title, preview.ifEmpty { null }, statusLabel, row.time, unreadLabel).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OtterAvatar(row.title)
        Spacer(Modifier.width(Dimens.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(row.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.status != null) { DeliveryStatusIcon(row.status); Spacer(Modifier.width(Dimens.s4)) }
                Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = if (row.previewIsSystem) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (row.unread > 0) {
                    Box(Modifier.padding(start = Dimens.s8).background(MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(horizontal = 7.dp, vertical = 1.dp)) {
                        Text(row.unread.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

