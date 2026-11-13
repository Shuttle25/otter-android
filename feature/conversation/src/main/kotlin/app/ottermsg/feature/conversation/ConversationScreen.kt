package app.ottermsg.feature.conversation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.ottermsg.core.ui.callLineText
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarResult
import app.ottermsg.core.data.calls.CallNotice
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import app.ottermsg.core.ui.components.AnchoredMenu
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.components.MenuAction
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterEmptyState
import app.ottermsg.core.ui.components.groupEventText
import androidx.compose.ui.res.pluralStringResource
import app.ottermsg.core.ui.R as UiR
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import kotlinx.coroutines.launch

/** The «Обновить» state of news bubbles (D68): shown only for a versionCode above [installedCode]. */
class NewsUpdate(
    val installedCode: Int = Int.MAX_VALUE, val state: UpdateUi = UpdateUi.Idle,
    val onUpdate: (app.ottermsg.proto.v1.AppUpdate) -> Unit = {},
)

/** Photo callbacks of the conversation screen. */
class PhotoActions(
    val onPick: () -> Unit = {}, val onCamera: () -> Unit = {}, val onOpen: (Long) -> Unit = {},
    val onDownload: (Long) -> Unit = {}, val onSave: (Long) -> Unit = {},
)

@Composable
fun ConversationRoute(
    onBack: () -> Unit, onOpenGroupInfo: (String) -> Unit = {}, onMessageInfo: (Long) -> Unit = {},
    onOpenPhoto: (Long) -> Unit = {},
    /** A call was dialed (or one is in progress): open the call screen. */
    onCallStarted: () -> Unit = {},
    vm: ConversationViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val pending by vm.pendingPhoto.collectAsStateWithLifecycle()
    val photoSending by vm.photoSending.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var cameraDenied by remember { mutableStateOf(false) }
    var saveAfterPermission by remember { mutableStateOf<Long?>(null) }
    // Visible conversation = read: on enter and whenever new incoming messages arrive while resumed.
    LaunchedEffect(ui.lastIncomingId) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.markRead() } }
    // While resumed this chat is the active one: incoming messages here post no notification (D62).
    LifecycleResumeEffect(vm.conversationId) {
        vm.onVisible(true)
        onPauseOrDispose { vm.onVisible(false) }
    }
    val unreadable = stringResource(R.string.conv_photo_unreadable)
    val saved = stringResource(R.string.conv_photo_saved)
    val saveFailed = stringResource(R.string.conv_photo_save_failed)
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            snackbar.showSnackbar(when (e) { PhotoEvent.Unreadable -> unreadable; PhotoEvent.Saved -> saved; PhotoEvent.SaveFailed -> saveFailed })
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.onPicked(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { vm.onCameraResult(it) }
    fun launchCamera() {
        val f = vm.newCameraFile()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".photos", f)
        runCatching { camera.launch(uri) }.onFailure { vm.onCameraResult(false) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else cameraDenied = true
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = saveAfterPermission
        saveAfterPermission = null
        if (granted && id != null) vm.saveToGallery(id)
    }
    // Calls (D80): microphone permission (first time) → push.status → warning → dial.
    val callWarning by vm.callWarning.collectAsStateWithLifecycle()
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.prepareCall() else micDenied = true
    }
    val startCall = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.prepareCall()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { vm.callStarted.collect { onCallStarted() } }
    val unavailable = stringResource(R.string.conv_call_unavailable)
    LaunchedEffect(Unit) { vm.callNotices.collect { if (it == CallNotice.NotAvailable) snackbar.showSnackbar(unavailable) } }
    val micText = stringResource(R.string.conv_call_mic)
    val settingsText = stringResource(R.string.conv_camera_settings)
    LaunchedEffect(micDenied) {
        if (!micDenied) return@LaunchedEffect
        micDenied = false
        if (snackbar.showSnackbar(micText, actionLabel = settingsText) == SnackbarResult.ActionPerformed) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val actions = PhotoActions(
        onPick = pickImage,
        onCamera = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        },
        onOpen = onOpenPhoto,
        onDownload = { vm.download(it) },
        onSave = { id ->
            if (vm.galleryNeedsPermission()) { saveAfterPermission = id; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            else vm.saveToGallery(id)
        },
    )
    CompositionLocalProvider(LocalPhotoSource provides vm.photoSource) {
        ConversationScreen(ui, text, sending, onBack, vm::onText, vm::send, { vm.retry(it) }, { vm.delete(it) }, vm.sent,
            onOpenGroupInfo = { if (ui.isGroup) onOpenGroupInfo(ui.groupIdHex) }, onMessageInfo = onMessageInfo,
            photoActions = actions, snackbar = snackbar,
            update = NewsUpdate(vm.installedCode, updateState, vm::startUpdate),
            onCall = startCall, onCallBack = { if (ui.canCall) startCall() })
    }
    if (callWarning) {
        AlertDialog(
            onDismissRequest = vm::dismissCallWarning,
            text = { Text(stringResource(R.string.conv_call_no_push)) },
            confirmButton = { TextButton(onClick = vm::confirmCall) { Text(stringResource(R.string.conv_call_anyway)) } },
            dismissButton = { TextButton(onClick = vm::dismissCallWarning) { Text(stringResource(R.string.conv_cancel)) } },
        )
    }
    pending?.let { path -> PhotoPreview(path, text, photoSending, onCancel = vm::cancelPhoto, onSend = vm::sendPhoto) }
    if (cameraDenied) {
        AlertDialog(
            onDismissRequest = { cameraDenied = false },
            text = { Text(stringResource(R.string.conv_camera_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    cameraDenied = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text(stringResource(R.string.conv_camera_settings)) }
            },
            dismissButton = { TextButton(onClick = { cameraDenied = false; pickImage() }) { Text(stringResource(R.string.conv_camera_gallery)) } },
        )
    }
}

@Composable
fun ConversationScreen(
    ui: ConversationUi, text: String, sending: Boolean,
    onBack: () -> Unit, onText: (String) -> Unit, onSend: () -> Unit,
    onRetry: (Long) -> Unit, onDelete: (Long) -> Unit,
    sentEvents: kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.emptyFlow(),
    onOpenGroupInfo: () -> Unit = {}, onMessageInfo: (Long) -> Unit = {},
    photoActions: PhotoActions = PhotoActions(), snackbar: SnackbarHostState = remember { SnackbarHostState() },
    update: NewsUpdate = NewsUpdate(),
    /** Tap on a call line: call this contact back. */
    onCallBack: () -> Unit = {},
    /** Top-bar call button (1:1 chats only, [ConversationUi.canCall]). */
    onCall: () -> Unit = {},
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
    val infoLabel = stringResource(R.string.conv_info)
    val saveLabel = stringResource(R.string.conv_photo_save)
    val language = LocalConfiguration.current.locales[0].language
    val res = LocalContext.current.resources

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            val title = if (ui.isNews) stringResource(R.string.news_title) else ui.title
            OtterTopBar(title, onBack = onBack, leading = {
                OtterAvatar(title, ui.seed, Dimens.avatarTopBar, family = ui.family, news = ui.isNews); Spacer(Modifier.width(Dimens.s12))
            }, onTitleClick = if (ui.isGroup) onOpenGroupInfo else null,
                subtitle = if (ui.isGroup) pluralStringResource(R.plurals.conv_members, ui.memberCount, ui.memberCount) else null,
                actions = {
                    if (ui.canCall) IconButton(onClick = onCall) { Icon(Icons.Outlined.Call, stringResource(R.string.conv_call)) }
                })
        },
        bottomBar = {
            if (ui.canWrite) MessageComposer(text, sending, onText, onSend, Modifier.navigationBarsPadding().imePadding(),
                onPickPhoto = photoActions.onPick, onTakePhoto = photoActions.onCamera)
            else if (!ui.isNews) NotMemberNotice(Modifier.navigationBarsPadding())
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loaded && ui.items.isEmpty()) {
                OtterEmptyState(
                    title = stringResource(R.string.conv_empty_title), text = stringResource(R.string.conv_empty_text),
                    illustration = UiR.drawable.otter_empty_chat, illustrationMax = 160.dp,
                )
            }
            LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                item { Spacer(Modifier.height(Dimens.s8)) }
                items(reversed, key = { it.key }) { item ->
                    when (item) {
                        is ConversationItem.DateChip -> DateChip(item.date)
                        is ConversationItem.Bubble -> if (item.message.call != null) {
                            val c = item.message.call
                            CallLineChip(callLineText(res, c.outcome, c.durationS), item.message.outgoing,
                                missed = c.outcome == app.ottermsg.core.data.calls.CallOutcome.MISSED.code, onClick = onCallBack)
                        } else if (item.message.resetName != null) {
                            SystemEventChip(stringResource(R.string.conv_system_reset, item.message.resetName))
                        } else if (item.message.event != null) {
                            SystemEventChip(groupEventText(item.message.event, ui.me, ui::nameOf))
                        } else if (item.message.news != null) {
                            // News (D64): read-only; the only action is copying the text.
                            val m = item.message
                            val newsText = newsText(m, language)
                            val u = m.news.update.takeIf { m.news.hasUpdate() && it.versionCode > update.installedCode }
                            NewsBubble(m, newsText, item.firstInGroup,
                                slot = { if (u != null) UpdateRow(u.versionName, update.state) { update.onUpdate(u) } },
                                onLongPress = { menuFor = m },
                                actions = listOf(CustomAccessibilityAction(copyLabel) { clipboard.setText(AnnotatedString(newsText)); true }),
                                menu = {
                                    AnchoredMenu(expanded = menuFor?.localId == m.localId, alignEnd = false,
                                        actions = listOf(MenuAction(copyLabel) { clipboard.setText(AnnotatedString(newsText)) }),
                                        onDismiss = { menuFor = null })
                                })
                        } else {
                            val m = item.message
                            val failed = m.outgoing && m.status == DeliveryStatus.FAILED
                            val photoReady = m.photo?.state == PhotoState.Ready
                            val canCopy = !m.system && m.text.isNotEmpty()
                            MessageBubble(
                                m, item.firstInGroup,
                                onLongPress = { menuFor = m },
                                onTap = {
                                    when {
                                        failed -> menuFor = m
                                        photoReady -> photoActions.onOpen(m.localId)
                                        m.photo?.state == PhotoState.Remote || m.photo?.state == PhotoState.Failed -> photoActions.onDownload(m.localId)
                                    }
                                },
                                onPhotoDownload = { photoActions.onDownload(m.localId) },
                                actions = buildList {
                                    if (failed) add(CustomAccessibilityAction(retryLabel) { onRetry(m.localId); true })
                                    if (photoReady) add(CustomAccessibilityAction(saveLabel) { photoActions.onSave(m.localId); true })
                                    if (canCopy) add(CustomAccessibilityAction(copyLabel) { clipboard.setText(AnnotatedString(m.text)); true })
                                    if (ui.isGroup && m.outgoing) add(CustomAccessibilityAction(infoLabel) { onMessageInfo(m.localId); true })
                                    add(CustomAccessibilityAction(deleteLabel) { confirmDelete = m; true })
                                },
                                menu = {
                                    AnchoredMenu(
                                        expanded = menuFor?.localId == m.localId, alignEnd = m.outgoing,
                                        actions = buildList {
                                            if (failed) add(MenuAction(retryLabel) { onRetry(m.localId) })
                                            if (photoReady) add(MenuAction(saveLabel) { photoActions.onSave(m.localId) })
                                            if (canCopy) add(MenuAction(copyLabel) { clipboard.setText(AnnotatedString(m.text)) })
                                            if (ui.isGroup && m.outgoing) add(MenuAction(infoLabel) { onMessageInfo(m.localId) })
                                            add(MenuAction(deleteLabel) { confirmDelete = m })
                                        },
                                        onDismiss = { menuFor = null },
                                    )
                                },
                            )
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

/** Calm notice instead of the composer once we are no longer a member of the group. */
@Composable
fun NotMemberNotice(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(Dimens.screenGutter), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.conv_not_member), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
