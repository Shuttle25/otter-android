package app.ottermsg.feature.groups

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.AnchoredMenu
import app.ottermsg.core.ui.components.MenuAction
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun GroupInfoRoute(onBack: () -> Unit, onAddMembers: (String) -> Unit, vm: GroupInfoViewModel = hiltViewModel()) {
    val meLabel = stringResource(R.string.group_you)
    LaunchedEffect(meLabel) { vm.meLabel.value = meLabel }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    GroupInfoScreen(ui, error, onBack, { onAddMembers(vm.groupId.toHex()) }, GroupActions(
        rename = vm::rename, setAdmin = vm::setAdmin, remove = vm::remove, leave = vm::leave, leaveAfterPromoting = vm::leaveAfterPromoting,
    ))
}

class GroupActions(
    val rename: (String) -> Unit = {}, val setAdmin: (ByteArray, Boolean) -> Unit = { _, _ -> }, val remove: (ByteArray) -> Unit = {},
    val leave: () -> Unit = {}, val leaveAfterPromoting: (ByteArray) -> Unit = {},
)

@Composable
fun GroupInfoScreen(ui: GroupInfoUi, error: Boolean, onBack: () -> Unit, onAddMembers: () -> Unit, actions: GroupActions) {
    var renaming by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<MemberUi?>(null) }
    var confirmRemove by remember { mutableStateOf<MemberUi?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var pickAdmin by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.group_info_title), onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.fillMaxWidth().padding(Dimens.screenGutter), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                    OtterAvatar(ui.name, ui.groupId, Dimens.avatarProfile, family = ui.family)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ui.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() })
                        if (ui.iAmAdmin) IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.group_rename))
                        }
                    }
                    Text(pluralStringResource(R.plurals.group_members_count, ui.memberCount, ui.memberCount),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!ui.iAmMember && ui.loaded) Text(stringResource(R.string.group_not_member), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (error) Text(stringResource(R.string.group_action_failed), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            if (ui.iAmAdmin) item { OtterSettingsRow(stringResource(R.string.group_add_members), icon = Icons.Outlined.PersonAdd, onClick = onAddMembers) }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            items(ui.members, key = { it.id.contentHashCode() }) { m ->
                val canManage = ui.iAmAdmin && !m.isMe
                val memberActions = if (!canManage) emptyList() else listOf(
                    if (m.admin) MenuAction(stringResource(R.string.group_unadmin)) { actions.setAdmin(m.id, false) }
                    else MenuAction(stringResource(R.string.group_make_admin)) { actions.setAdmin(m.id, true) },
                    MenuAction(stringResource(R.string.group_remove)) { confirmRemove = m },
                )
                MemberRow(m, memberActions, menuOpen = menuFor?.id?.contentEquals(m.id) == true,
                    onOpenMenu = { if (canManage) menuFor = m }, onDismissMenu = { menuFor = null })
            }
            if (ui.iAmMember) {
                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin)
                        .clickable { if (ui.mustPickAdminBeforeLeaving) pickAdmin = true else confirmLeave = true }
                        .padding(horizontal = Dimens.screenGutter), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(Dimens.s16))
                        Text(stringResource(R.string.group_leave), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (renaming) RenameDialog(ui.name, onDismiss = { renaming = false }) { renaming = false; actions.rename(it) }
    confirmRemove?.let { m ->
        AlertDialog(onDismissRequest = { confirmRemove = null },
            text = { Text(stringResource(R.string.group_remove_confirm, m.name)) },
            confirmButton = { TextButton(onClick = { confirmRemove = null; actions.remove(m.id) }) { Text(stringResource(R.string.group_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.group_cancel)) } })
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        text = { Text(stringResource(R.string.group_leave_confirm)) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; actions.leave() }) { Text(stringResource(R.string.group_leave)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.group_cancel)) } })
    if (pickAdmin) AlertDialog(onDismissRequest = { pickAdmin = false },
        title = { Text(stringResource(R.string.group_pick_admin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.group_pick_admin_text), style = MaterialTheme.typography.bodyMedium)
                ui.members.filterNot { it.isMe }.forEach { m ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch).clickable { pickAdmin = false; actions.leaveAfterPromoting(m.id) },
                        verticalAlignment = Alignment.CenterVertically) {
                        OtterAvatar(m.name, m.id, size = 32.dp); Spacer(Modifier.width(Dimens.s12)); Text(m.name)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = { pickAdmin = false }) { Text(stringResource(R.string.group_cancel)) } })
}

/** Member row: TalkBack reads "name, админ"; admin actions via tap (anchored menu) and custom accessibility actions. */
@Composable
private fun MemberRow(m: MemberUi, actions: List<MenuAction>, menuOpen: Boolean, onOpenMenu: () -> Unit, onDismissMenu: () -> Unit) {
    val adminLabel = stringResource(R.string.group_admin_badge)
    val description = if (m.admin) "${m.name}, $adminLabel" else m.name
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin)
                .let { if (actions.isNotEmpty()) it.clickable(onClick = onOpenMenu) else it }
                .clearAndSetSemantics {
                    contentDescription = description
                    if (actions.isNotEmpty()) {
                        onClick { onOpenMenu(); true }
                        customActions = actions.map { a -> CustomAccessibilityAction(a.label) { a.onClick(); true } }
                    }
                }
                .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OtterAvatar(m.name, m.id, size = 40.dp)
            Spacer(Modifier.width(Dimens.s12))
            Text(m.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (m.admin) Surface(shape = RoundedCornerShape(Dimens.s8), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(adminLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Dimens.s8, vertical = 2.dp))
            }
        }
        AnchoredMenu(menuOpen, alignEnd = true, actions = actions, onDismiss = onDismissMenu)
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = text.trim().length in 1..NewGroupUi.MAX_NAME
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_rename)) },
        text = { OutlinedTextField(text, { text = it.take(NewGroupUi.MAX_NAME) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = valid && text.trim() != current) { Text(stringResource(R.string.group_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.group_cancel)) } })
}
