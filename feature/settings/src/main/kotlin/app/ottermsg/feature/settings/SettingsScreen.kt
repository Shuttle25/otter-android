package app.ottermsg.feature.settings

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
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import app.ottermsg.core.ui.components.OtterAvatar
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
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
class SettingsViewModel @Inject constructor(accounts: AccountRepository, sync: SyncEngine) : ViewModel() {
    val account = accounts.account
    val connection = sync.connection
}

fun connectionLabel(state: ConnectionState): Int = when (state) {
    is ConnectionState.Online -> R.string.settings_online
    ConnectionState.Connecting -> R.string.settings_connecting
    else -> R.string.settings_offline
}

/** Settings: profile, family invite, connection. Other sections of guide §10 arrive with their features. */
@Composable
fun SettingsRoute(onBack: () -> Unit, onInvite: () -> Unit, onProfile: () -> Unit, onPhotos: () -> Unit = {}, onBackup: () -> Unit = {},
                  onNotifications: () -> Unit = {}, onNetLog: () -> Unit = {}, versionText: String = "", vm: SettingsViewModel = hiltViewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.settings_profile))
            account?.let { ProfileRow(it.displayName, it.username, it.userId, onProfile) }
            Section(stringResource(R.string.settings_family))
            OtterSettingsRow(stringResource(R.string.settings_invite), icon = Icons.Outlined.PersonAdd, onClick = onInvite)
            Section(stringResource(R.string.settings_media))
            OtterSettingsRow(stringResource(R.string.settings_photos), icon = Icons.Outlined.PhotoLibrary, onClick = onPhotos)
            Section(stringResource(R.string.settings_notifications_section))
            OtterSettingsRow(stringResource(R.string.settings_notifications), icon = Icons.Outlined.Notifications, onClick = onNotifications)
            Section(stringResource(R.string.settings_security))
            OtterSettingsRow(stringResource(R.string.settings_backup), icon = Icons.Outlined.CloudDone, onClick = onBackup)
            Section(stringResource(R.string.settings_connection))
            val online = connection as? ConnectionState.Online
            OtterSettingsRow(stringResource(connectionLabel(connection)), subtitle = online?.let { "${it.nodeLabel} · ${it.url}" },
                icon = Icons.Outlined.Wifi)
            OtterSettingsRow(stringResource(R.string.settings_netlog), icon = Icons.AutoMirrored.Outlined.ListAlt, onClick = onNetLog)
            if (versionText.isNotEmpty()) VersionFooter(versionText)
        }
    }
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

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Dimens.s24, bottom = Dimens.s8)
            .semantics { heading() })
}
