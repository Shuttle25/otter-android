package app.ottermsg.feature.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.push.PushMode
import app.ottermsg.core.data.push.PushMonitor
import app.ottermsg.core.data.settings.Settings as AppSettings
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUi(val mode: PushMode, val enabled: Boolean)

/** «Уведомления» (D61/D62): the push mode, the in-app switch, and a link to Android's notification settings. */
@HiltViewModel
class NotificationsViewModel internal constructor(
    mode: Flow<PushMode>, enabled: Flow<Boolean>, private val save: suspend (Boolean) -> Unit,
) : ViewModel() {
    @Inject constructor(monitor: PushMonitor, settings: AppSettings) :
        this(monitor.observeMode(), settings.observeNotificationsEnabled(), { settings.setNotificationsEnabled(it) })

    val ui: StateFlow<NotificationsUi?> = combine(mode, enabled) { m, e -> NotificationsUi(m, e) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setEnabled(v: Boolean) { viewModelScope.launch { save(v) } }
}

@Composable
fun NotificationsRoute(onBack: () -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-read on every resume: the member may come back from Android's settings with notifications allowed.
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val systemOn = remember(state) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    NotificationsScreen(ui, systemOn, onBack, vm::setEnabled, onSystem = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    })
}

@Composable
fun NotificationsScreen(ui: NotificationsUi?, systemOn: Boolean, onBack: () -> Unit, onEnabled: (Boolean) -> Unit, onSystem: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_notifications), onBack = onBack) }) { padding ->
        val u = ui ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val fast = u.mode == PushMode.FAST
            OtterSettingsRow(stringResource(if (fast) R.string.notif_mode_fast else R.string.notif_mode_saver),
                subtitle = stringResource(if (fast) R.string.notif_mode_fast_hint else R.string.notif_mode_saver_hint),
                icon = if (fast) Icons.Outlined.NotificationsActive else Icons.Outlined.BatterySaver)
            if (!systemOn) OtterSettingsRow(stringResource(R.string.notif_blocked), icon = Icons.Outlined.NotificationsOff)
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin)
                .toggleable(value = u.enabled, onValueChange = onEnabled, role = Role.Switch)
                .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notif_show), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = u.enabled, onCheckedChange = null)
            }
            OtterSettingsRow(stringResource(R.string.notif_system), icon = Icons.Outlined.SettingsIcon, onClick = onSystem)
        }
    }
}
