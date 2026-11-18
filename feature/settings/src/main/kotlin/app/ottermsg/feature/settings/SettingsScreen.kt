package app.ottermsg.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.outlined.PhoneLocked
import androidx.compose.material3.Switch
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.core.data.settings.Settings as AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import app.ottermsg.core.ui.components.OtterAvatar
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel internal constructor(
    val account: StateFlow<LocalAccount?>, val connection: StateFlow<ConnectionState>,
    relayOnly: Flow<Boolean>, private val saveRelayOnly: suspend (Boolean) -> Unit,
    appLockOn: Flow<Boolean> = emptyFlow(), screenSecure: Flow<Boolean> = emptyFlow(),
    private val saveScreenSecure: suspend (Boolean) -> Unit = {}, themeMode: Flow<Int> = emptyFlow(),
) : ViewModel() {
    @Inject constructor(accounts: AccountRepository, sync: SyncEngine, settings: AppSettings) :
        this(accounts.account, sync.connection, settings.observeCallsRelayOnly(), { settings.setCallsRelayOnly(it) },
            settings.observeAppLockOn(), settings.observeScreenSecure(), { settings.setScreenSecure(it) }, settings.observeThemeMode())

    /** «Звонки только через сервер» (D79), on by default. */
    val callsRelayOnly: StateFlow<Boolean> = relayOnly.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setCallsRelayOnly(v: Boolean) { viewModelScope.launch { saveRelayOnly(v) } }

    /** «Блокировка приложения» subtitle (D121). */
    val appLockOn: StateFlow<Boolean> = appLockOn.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** «Запрет скриншотов» (D121): MainActivity applies it at once. */
    val screenSecure: StateFlow<Boolean> = screenSecure.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setScreenSecure(v: Boolean) { viewModelScope.launch { saveScreenSecure(v) } }
    /** «Оформление» subtitle (D124). */
    val themeMode: StateFlow<Int> = themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
}

fun connectionLabel(state: ConnectionState): Int = when (state) {
    is ConnectionState.Online -> R.string.settings_online
    ConnectionState.Connecting -> R.string.settings_connecting
    else -> R.string.settings_offline
}

/** Settings: one plain list of rows, no section headers (owner 2026-11-16). */
@Composable
fun SettingsRoute(onBack: () -> Unit, onInvite: () -> Unit, onProfile: () -> Unit, onPhotos: () -> Unit = {}, onBackup: () -> Unit = {},
                  onNotifications: () -> Unit = {}, onNetwork: () -> Unit = {}, onAppLock: () -> Unit = {},
                  onAppearance: () -> Unit = {}, onPhrase: () -> Unit = {}, versionText: String = "", vm: SettingsViewModel = hiltViewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val relayOnly by vm.callsRelayOnly.collectAsStateWithLifecycle()
    val appLockOn by vm.appLockOn.collectAsStateWithLifecycle()
    val screenSecure by vm.screenSecure.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-read on every resume: the member may come back from Android's settings with full-screen ringing allowed.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val fsiBlocked = remember(lifecycle) {
        Build.VERSION.SDK_INT >= 34 && !NotificationManagerCompat.from(context).canUseFullScreenIntent()
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.heightIn(min = Dimens.s8))
            account?.let { ProfileRow(it.displayName, it.username, it.userId, onProfile) }
            OtterSettingsRow(stringResource(R.string.settings_invite), icon = Icons.Outlined.PersonAdd, onClick = onInvite)
            OtterSettingsRow(stringResource(R.string.settings_notifications), icon = Icons.Outlined.Notifications, onClick = onNotifications)
            OtterSettingsRow(stringResource(R.string.settings_appearance), subtitle = stringResource(appearanceLabel(themeMode)),
                icon = Icons.Outlined.Palette, onClick = onAppearance)
            OtterSettingsRow(stringResource(R.string.settings_photos), icon = Icons.Outlined.PhotoLibrary, onClick = onPhotos)
            SecurityRows(appLockOn, onAppLock, screenSecure, vm::setScreenSecure)
            OtterSettingsRow(stringResource(R.string.settings_backup), icon = Icons.Outlined.CloudDone, onClick = onBackup)
            OtterSettingsRow(stringResource(R.string.settings_phrase), icon = Icons.Outlined.Key, onClick = onPhrase)
            val online = connection as? ConnectionState.Online
            // label and transport only (never the address); the row opens «Состояние сети» (D123)
            OtterSettingsRow(stringResource(connectionLabel(connection)), subtitle = online?.let { "${it.nodeLabel} · ${transportOf(it.url)}" },
                icon = Icons.Outlined.Wifi, onClick = onNetwork)
            CallSettingsRows(relayOnly, vm::setCallsRelayOnly, fsiBlocked, onFsiFix = {
                if (Build.VERSION.SDK_INT >= 34) runCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
            if (versionText.isNotEmpty()) VersionFooter(versionText)
        }
    }
}

/**
 * Call rows: the relay-only switch and, when Android 14+ forbids full-screen ringing on the lock screen, a one-tap fix
 * that opens the system page.
 */
@Composable
internal fun CallSettingsRows(relayOnly: Boolean, onRelayOnly: (Boolean) -> Unit, fsiBlocked: Boolean, onFsiFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin)
        .toggleable(value = relayOnly, onValueChange = onRelayOnly, role = Role.Switch)
        .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.s16))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_calls_relay_only), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_calls_relay_only_sub), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = relayOnly, onCheckedChange = null)
    }
    if (fsiBlocked) OtterSettingsRow(stringResource(R.string.settings_calls_fsi), icon = Icons.Outlined.PhoneLocked, onClick = onFsiFix)
}

/** «Otter 0.5.0 (abc1234)» at the very bottom of Settings (D63). */
@Composable
fun VersionFooter(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s24))
}

/** Avatar 48 dp, name, @username and a chevron; the whole row opens Profile. */
@Composable
private fun ProfileRow(name: String, username: String, userId: ByteArray, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin).clickable(onClick = onClick)
            .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OtterAvatar(name, seed = userId, size = Dimens.avatarList)
        Spacer(Modifier.width(Dimens.s16))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text("@$username", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section heading inside sub-screens (e.g. «Состояние сети»); the main Settings list has none (owner 2026-11-16). */
@Composable
internal fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Dimens.s24, bottom = Dimens.s8)
            .semantics { heading() })
}
