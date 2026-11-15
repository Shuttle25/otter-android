package app.ottermsg.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.database.dao.NetLogDao
import app.ottermsg.core.database.entity.NetLogEntity
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class NetLogViewModel @Inject constructor(dao: NetLogDao) : ViewModel() {
    val rows = dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** One log line: time span (local), node, endpoint, network (+operator), result, ×count, detail for failures (D74); call lines always show outcome, duration and RTT. */
internal fun formatLine(e: NetLogEntity, zone: ZoneId, mobileLabel: String): String {
    val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone)
    val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    val first = Instant.ofEpochMilli(e.firstMs); val last = Instant.ofEpochMilli(e.lastMs)
    val span = if (e.count > 1) "${fmt.format(first)}–${hm.format(last)}" else fmt.format(last)
    val net = when (e.network) {
        "wifi" -> "Wi-Fi"
        "mobile" -> listOf(mobileLabel, e.operator).filter { it.isNotBlank() }.joinToString(" ")
        else -> e.network
    }
    val tail = when {
        // call lines (M8): outcome + duration and the media RTT are the point of the line, also when it worked
        e.endpoint.startsWith("call:") -> " (" + listOfNotNull(e.detail.ifBlank { null }, e.rttMs.takeIf { it > 0 }?.let { "$it ms" })
            .joinToString(", ") + ")"
        e.result != "ok" && e.detail.isNotBlank() -> " (${e.detail})"
        else -> ""
    }.takeIf { it != " ()" }.orEmpty()
    return "$span  ${e.nodeLabel}  ${e.endpoint}  $net  ${e.result}  ×${e.count}$tail"
}

private val IPV4 = Regex("""(?<![\w.])\d{1,3}(?:\.\d{1,3}){3}(?!\d)(?!\.\d)""")

/** A colon-hex token (optionally ending in a dotted IPv4 part); [isIpv6] decides whether it is an address. */
private val IPV6 = Regex("""(?<![\w:.])[0-9A-Fa-f:]*:[0-9A-Fa-f:]*(?:\d{1,3}(?:\.\d{1,3}){3})?(?![\w:])(?!\.\d)""")

/** `::` once, or eight groups (seven with an embedded IPv4) of 1–4 hex digits: so `12:00:05` or `ru-1:27443` stay. */
private fun isIpv6(t: String): Boolean {
    val v4 = t.substringAfterLast(':').contains('.')
    val hex = if (v4) t.substringBeforeLast(':') + ":" else t
    if (hex.contains(":::")) return false
    val compressed = hex.indexOf("::").let { it >= 0 && hex.indexOf("::", it + 1) < 0 }
    val groups = hex.split(':').filter { it.isNotEmpty() }
    if (groups.any { it.length > 4 }) return false
    val needed = if (v4) 6 else 8
    return if (compressed) groups.size < needed else groups.size == needed && hex.count { it == ':' } == (if (v4) 6 else 7)
}

/** Host and port of a log endpoint `scheme://host[:port]` (IPv6 in brackets); port is empty when absent. */
private fun hostAndPort(hostPort: String): Pair<String, String> = when {
    hostPort.startsWith("[") -> hostPort.substringAfter('[').substringBefore(']') to
        hostPort.substringAfter("]", "").removePrefix(":")
    ':' in hostPort -> hostPort.substringBeforeLast(':') to hostPort.substringAfterLast(':')
    else -> hostPort to ""
}

/**
 * The shared form of a line (C24, D96): node label instead of host (`wss:27443`), no mobile operator, the endpoint's
 * host replaced by the label and any other IP address by `<ip>` in the error text. Times stay at minute resolution.
 */
internal fun shareLine(e: NetLogEntity, zone: ZoneId, mobileLabel: String): String {
    if (e.endpoint.startsWith("call:")) return formatLine(e.copy(operator = ""), zone, mobileLabel)
    val scheme = e.endpoint.substringBefore("://")
    val (host, port) = hostAndPort(e.endpoint.substringAfter("://", ""))
    val endpoint = if (port.isEmpty()) scheme else "$scheme:$port"
    var detail = if (host.isNotEmpty()) e.detail.replace(host, e.nodeLabel, ignoreCase = true) else e.detail
    detail = IPV6.replace(detail) { m -> if (isIpv6(m.value)) "<ip>" else m.value }
    detail = IPV4.replace(detail, "<ip>")
    return formatLine(e.copy(endpoint = endpoint, operator = "", detail = detail), zone, mobileLabel)
}

/** The shared text: «Otter <version> · <date>», then the lines (newest first). */
internal fun shareText(versionText: String, date: LocalDate, lines: List<String>): String =
    (listOf("$versionText · $date") + lines).joinToString("\n")

/** Settings → Подключение → «Журнал подключений»: the phone's local connectivity log, shared as plain text. */
@Composable
fun NetLogRoute(onBack: () -> Unit, versionText: String, vm: NetLogViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val mobile = stringResource(R.string.netlog_mobile)
    val shareTitle = stringResource(R.string.netlog_share)
    val zone = ZoneId.systemDefault()
    val lines = rows.map { formatLine(it, zone, mobile) }
    NetLogScreen(lines, onBack, onShare = {
        // the shared copy is redacted (C24, D96); the screen keeps the full lines
        val shared = rows.map { shareLine(it, zone, mobile) }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, shareText(versionText, LocalDate.now(), shared))
        runCatching { ctx.startActivity(Intent.createChooser(send, shareTitle)) }
    })
}

@Composable
fun NetLogScreen(lines: List<String>, onBack: () -> Unit, onShare: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            OtterTopBar(stringResource(R.string.settings_netlog), onBack = onBack, actions = {
                IconButton(onClick = onShare, enabled = lines.isNotEmpty()) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.netlog_share))
                }
            })
        }) { padding ->
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.netlog_empty), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(lines) { l ->
                    Text(l, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
                }
            }
        }
    }
}
