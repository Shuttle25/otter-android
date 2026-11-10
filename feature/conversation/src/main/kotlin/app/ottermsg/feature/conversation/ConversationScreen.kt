package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import kotlinx.coroutines.launch

@Composable
fun ConversationRoute(onBack: () -> Unit, vm: ConversationViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Visible conversation = read: on enter and whenever new incoming messages arrive while resumed.
    LaunchedEffect(ui.lastIncomingId) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.markRead() } }
    ConversationScreen(ui, text, sending, onBack, vm::onText, vm::send, { vm.retry(it) }, { vm.delete(it) }, vm.sent)
}

@Composable
fun ConversationScreen(
    ui: ConversationUi, text: String, sending: Boolean,
    onBack: () -> Unit, onText: (String) -> Unit, onSend: () -> Unit,
    onRetry: (Long) -> Unit, onDelete: (Long) -> Unit,
    sentEvents: kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.emptyFlow(),
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val reversed = remember(ui.items) { ui.items.asReversed() } // reverseLayout: index 0 is the newest, list starts at the bottom
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    var newWhileAway by remember { mutableIntStateOf(0) }
    var lastSeenIncoming by remember { mutableStateOf(ui.lastIncomingId) }
    var menuFor by remember { mutableStateOf<MessageUi?>(null) }
    var confirmDelete by remember { mutableStateOf<MessageUi?>(null) }

    LaunchedEffect(ui.lastIncomingId) {
        if (ui.lastIncomingId != lastSeenIncoming) {
            if (!atBottom) newWhileAway++ else listState.animateScrollToItem(0)
            lastSeenIncoming = ui.lastIncomingId
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) newWhileAway = 0 }
    LaunchedEffect(Unit) { sentEvents.collect { listState.scrollToItem(0) } }

    val copyLabel = stringResource(R.string.conv_copy)
    val deleteLabel = stringResource(R.string.conv_delete)
    val retryLabel = stringResource(R.string.conv_retry)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            OtterTopBar(ui.title, onBack = onBack, leading = {
                OtterAvatar(ui.title, Dimens.avatarTopBar); Spacer(Modifier.width(Dimens.s12))
            })
        },
        bottomBar = { MessageComposer(text, sending, onText, onSend, Modifier.navigationBarsPadding().imePadding()) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(Dimens.s8)) }
                items(reversed, key = { it.key }) { item ->
                    when (item) {
                        is ConversationItem.DateChip -> DateChip(item.date)
                        is ConversationItem.Bubble -> {
                            val m = item.message
                            val failed = m.outgoing && m.status == DeliveryStatus.FAILED
                            Box {
                                MessageBubble(
                                    m, item.firstInGroup,
                                    onLongPress = { menuFor = m },
                                    onTap = { if (failed) menuFor = m },
                                    actions = buildList {
                                        if (failed) add(CustomAccessibilityAction(retryLabel) { onRetry(m.localId); true })
                                        if (!m.system) add(CustomAccessibilityAction(copyLabel) { clipboard.setText(AnnotatedString(m.text)); true })
                                        add(CustomAccessibilityAction(deleteLabel) { confirmDelete = m; true })
                                    },
                                )
                                DropdownMenu(expanded = menuFor?.localId == m.localId, onDismissRequest = { menuFor = null }) {
                                    if (failed) DropdownMenuItem(text = { Text(retryLabel) }, onClick = { menuFor = null; onRetry(m.localId) })
                                    if (!m.system) DropdownMenuItem(text = { Text(copyLabel) },
                                        onClick = { menuFor = null; clipboard.setText(AnnotatedString(m.text)) })
                                    DropdownMenuItem(text = { Text(deleteLabel) }, onClick = { menuFor = null; confirmDelete = m })
                                }
                            }
                        }
                    }
                }
            }
            if (!atBottom) {
                val jump = stringResource(R.string.conv_jump)
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Dimens.s16),
                ) {
                    BadgedBox(badge = { if (newWhileAway > 0) Badge { Text("$newWhileAway") } }) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = jump)
                    }
                }
            }
        }
    }

    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.conv_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(m.localId) }) { Text(stringResource(R.string.conv_delete_ok)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.conv_cancel)) } },
        )
    }
}
