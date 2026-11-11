package app.ottermsg.feature.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
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

/** M1 settings: profile, family invite, connection. Other sections of guide §10 arrive with their features. */
@Composable
fun SettingsRoute(onBack: () -> Unit, onInvite: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.settings_profile))
            OtterSettingsRow(account?.displayName.orEmpty(), subtitle = account?.username?.let { "@$it" }, icon = Icons.Outlined.Person)
            Section(stringResource(R.string.settings_family))
            OtterSettingsRow(stringResource(R.string.settings_invite), icon = Icons.Outlined.PersonAdd, onClick = onInvite)
            Section(stringResource(R.string.settings_connection))
            val online = connection as? ConnectionState.Online
            OtterSettingsRow(stringResource(connectionLabel(connection)), subtitle = online?.let { "${it.nodeLabel} · ${it.url}" },
                icon = Icons.Outlined.Wifi)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Dimens.s24, bottom = Dimens.s8)
            .semantics { heading() })
}
