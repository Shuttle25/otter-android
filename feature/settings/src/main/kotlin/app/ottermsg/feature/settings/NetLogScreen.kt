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

/** One log line: time span (local), node, endpoint, network (+operator), result, ×count, detail for failures (D74). */
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
    val tail = if (e.result != "ok" && e.detail.isNotBlank()) " (${e.detail})" else ""
    return "$span  ${e.nodeLabel}  ${e.endpoint}  $net  ${e.result}  ×${e.count}$tail"
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
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, shareText(versionText, LocalDate.now(), lines))
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
