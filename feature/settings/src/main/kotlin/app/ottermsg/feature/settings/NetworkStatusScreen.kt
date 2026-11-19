package app.ottermsg.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.SettingsApplications
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.crypto.NetworkCode
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.network.CurrentNetwork
import app.ottermsg.core.data.network.EndpointRow
import app.ottermsg.core.data.network.NetworkStatus
import app.ottermsg.core.data.network.NodeRow
import app.ottermsg.core.data.push.PushMode
import app.ottermsg.core.data.push.PushMonitor
import app.ottermsg.core.data.settings.Settings as AppSettings
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Everything «Состояние сети» shows; the battery state is read by the route (it changes outside the app). */
data class NetworkStatusUi(
    val connection: ConnectionState,
    /** When the current connection's endpoint last connected (its `endpoint_stats` success). */
    val sinceMs: Long?,
    /** `CurrentNetwork` type: wifi | mobile | other | none. */
    val network: String, val operator: String,
    val nodes: List<NodeRow>,
    val pushMode: PushMode, val googleWake: Boolean, val lastWakeMs: Long,
    val nowMs: Long,
    /** D139: the 15-digit network code (`ddddd ddddd ddddd`); empty before registration. */
    val networkCode: String = "",
)

/** «WSS» / «WS» from the connected URL: the screens name the node by label and transport, never by address. */
fun transportOf(url: String): String = url.substringBefore("://").uppercase()

/**
 * «Состояние сети» (D123): refreshed on every connection change and every [tickMs] while the screen is visible; only
 * reads what the phone already has (manifest, endpoint history, settings). Nothing is sent anywhere.
 */
@HiltViewModel
class NetworkStatusViewModel internal constructor(
    connection: StateFlow<ConnectionState>, nodes: suspend () -> List<NodeRow>, network: () -> Pair<String, String>,
    mode: Flow<PushMode>, googleWake: Flow<Boolean>, lastWake: suspend () -> Long, now: () -> Long,
    tickMs: Long = TICK_MS, networkCode: suspend () -> String = { "" },
) : ViewModel() {
    @Inject constructor(status: NetworkStatus, sync: SyncEngine, net: CurrentNetwork, push: PushMonitor,
                        settings: AppSettings, clock: Clock) :
        this(sync.connection, status::nodes, net::current, push.observeMode(), settings.observeGoogleWake(),
            settings::pushLastWakeMs, clock::now,
            networkCode = { kotlin.runCatching { NetworkCode.of(settings.networkPub()) }.getOrDefault("") })

    private val ticks = flow { while (true) { emit(Unit); delay(tickMs) } }

    val ui: StateFlow<NetworkStatusUi?> = combine(connection, ticks, mode, googleWake) { c, _, m, w ->
        val rows = nodes()
        val since = (c as? ConnectionState.Online)?.let { on ->
            rows.flatMap { it.endpoints }.firstOrNull { it.url == on.url }?.lastSuccessMs
        }
        val (type, operator) = network()
        NetworkStatusUi(c, since, type, operator, rows, m, w, lastWake(), now(), networkCode())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object { const val TICK_MS = 10_000L }
}

@Composable
fun NetworkStatusRoute(onBack: () -> Unit, onNetLog: () -> Unit, vm: NetworkStatusViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-read on every resume: the member comes back from Android's battery dialog or app page.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val restricted = remember(lifecycle) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false
    }
    val onBattery = {
        // A shortcut to Android's own dialog only (owner rule: phones are woken by Firebase, never kept awake by Otter).
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.recoverCatching { // some vendors remove the dialog: fall back to the list
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Unit
    }
    val onAppDetails = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Unit
    }
    NetworkStatusContent(ui, restricted, onBack, onBattery, onAppDetails, onNetLog)
}

@Composable
fun NetworkStatusContent(
    ui: NetworkStatusUi?, batteryRestricted: Boolean, onBack: () -> Unit, onBattery: () -> Unit, onAppDetails: () -> Unit,
    onNetLog: () -> Unit, zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.network_title), onBack = onBack) }) { padding ->
        val u = ui ?: return@Scaffold
        val time = TimeText(zone, u.nowMs)
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (u.networkCode.isNotEmpty()) {
                val spoken = stringResource(R.string.network_code) + ": " + NetworkCode.spoken(u.networkCode)
                Box(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
                    OtterSettingsRow(stringResource(R.string.network_code), subtitle = u.networkCode, icon = Icons.Outlined.VerifiedUser)
                }
            }
            Section(stringResource(R.string.network_now))
            NowRow(u, time)
            Section(stringResource(R.string.network_phone))
            OtterSettingsRow(when (u.network) {
                "wifi" -> stringResource(R.string.network_wifi)
                "mobile" -> listOf(stringResource(R.string.network_mobile), u.operator).filter { it.isNotBlank() }.joinToString(" · ")
                "none" -> stringResource(R.string.network_none)
                else -> stringResource(R.string.network_other)
            }, icon = when (u.network) {
                "wifi" -> Icons.Outlined.Wifi
                "mobile" -> Icons.Outlined.SignalCellularAlt
                else -> Icons.Outlined.WifiOff
            })
            Section(stringResource(R.string.network_nodes))
            u.nodes.forEach { NodeCard(it, time, u.nowMs) }
            Section(stringResource(R.string.settings_notifications_section))
            val fast = u.pushMode == PushMode.FAST
            OtterSettingsRow(stringResource(if (fast) R.string.notif_mode_fast else R.string.notif_mode_saver),
                subtitle = if (u.lastWakeMs > 0) stringResource(R.string.network_last_wake, time.of(u.lastWakeMs))
                    else stringResource(if (u.googleWake) R.string.network_no_wake else R.string.notif_google_wake_off_warning),
                icon = if (fast) Icons.Outlined.NotificationsActive else Icons.Outlined.BatterySaver)
            Section(stringResource(R.string.network_battery))
            if (batteryRestricted) {
                OtterSettingsRow(stringResource(R.string.network_battery_restricted), icon = Icons.Outlined.BatteryAlert)
                TextButton(onClick = onBattery, modifier = Modifier.padding(horizontal = Dimens.s8)) {
                    Text(stringResource(R.string.network_battery_fix))
                }
            } else {
                OtterSettingsRow(stringResource(R.string.network_battery_ok), icon = Icons.Outlined.BatteryFull)
            }
            OtterSettingsRow(stringResource(R.string.network_autostart), subtitle = stringResource(R.string.network_autostart_hint),
                icon = Icons.Outlined.SettingsApplications, onClick = onAppDetails)
            Section(stringResource(R.string.settings_connection))
            OtterSettingsRow(stringResource(R.string.settings_netlog), icon = Icons.AutoMirrored.Outlined.ListAlt, onClick = onNetLog)
        }
    }
}

/** Local times: «HH:mm» today, «dd.MM HH:mm» on another day. */
private class TimeText(private val zone: ZoneId, nowMs: Long) {
    private val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    private val dayHm = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone)
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone)
    fun of(ms: Long): String {
        val t = Instant.ofEpochMilli(ms)
        return if (t.atZone(zone).toLocalDate() == today) hm.format(t) else dayHm.format(t)
    }
    fun seconds(ms: Long): String = hms.format(Instant.ofEpochMilli(ms))
}

@Composable
private fun NowRow(u: NetworkStatusUi, time: TimeText) {
    when (val c = u.connection) {
        is ConnectionState.Online -> {
            val transport = transportOf(c.url)
            OtterSettingsRow(
                if (u.sinceMs != null) stringResource(R.string.network_online_since, c.nodeLabel, transport, time.of(u.sinceMs))
                else stringResource(R.string.network_online, c.nodeLabel, transport),
                icon = Icons.Outlined.Wifi)
        }
        ConnectionState.Connecting -> OtterSettingsRow(stringResource(R.string.settings_connecting), icon = Icons.Outlined.Wifi)
        is ConnectionState.Waiting -> OtterSettingsRow(stringResource(R.string.network_waiting, time.seconds(c.retryAtMs)),
            subtitle = maskIps(c.reason).ifBlank { null }, icon = Icons.Outlined.WifiOff)
        ConnectionState.Offline -> OtterSettingsRow(stringResource(R.string.settings_offline), icon = Icons.Outlined.WifiOff)
    }
}

/** One node: its manifest label, then one line per endpoint with this phone's history. */
@Composable
private fun NodeCard(node: NodeRow, time: TimeText, nowMs: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8)) {
        Text(node.label, style = MaterialTheme.typography.titleMedium)
        node.version?.let {
            Text(stringResource(R.string.network_node_version, it), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        node.endpoints.forEach { e ->
            Text(endpointLine(e, time, nowMs), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun endpointLine(e: EndpointRow, time: TimeText, nowMs: Long): String {
    val name = if (e.cdn) "${e.name} · CDN" else e.name
    val parts = mutableListOf<String>()
    if (e.neverTried) parts += stringResource(R.string.network_never)
    e.lastSuccessMs?.let { parts += stringResource(R.string.network_last_ok, time.of(it)) }
    e.rttMs?.let { parts += stringResource(R.string.network_rtt, it) }
    e.lastFailureMs?.let { parts += stringResource(R.string.network_last_fail, time.of(it)) }
    if (e.failures > 0) parts += stringResource(R.string.network_failures, e.failures)
    if (e.demotedUntilMs > nowMs) parts += stringResource(R.string.network_demoted, time.of(e.demotedUntilMs))
    return (listOf(name) + parts).joinToString(" · ")
}
