package app.ottermsg.feature.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.data.calls.CallNotice
import app.ottermsg.core.ui.callLineText
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.theme.Dimens

private val HangUpRed = Color(0xFFC62828)
private val AcceptGreen = Color(0xFF2E7D32)

/** The call screen (D80); [onAccept] goes through the activity (microphone permission first). */
@Composable
fun CallRoute(vm: CallViewModel, onAccept: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val warn by vm.warnPeer.collectAsStateWithLifecycle()
    CallScreen(ui, onAccept = onAccept, onDecline = vm::decline, onHangUp = vm::hangUp, onMute = vm::toggleMute,
        onSpeaker = vm::toggleSpeaker)
    if (warn != null) {
        AlertDialog(
            onDismissRequest = vm::cancelCallBack,
            text = { Text(stringResource(R.string.call_no_push)) },
            confirmButton = { TextButton(onClick = vm::confirmCallBack) { Text(stringResource(R.string.call_anyway)) } },
            dismissButton = { TextButton(onClick = vm::cancelCallBack) { Text(stringResource(R.string.call_cancel)) } },
        )
    }
}

@Composable
fun statusText(s: CallStatus): String {
    val res = LocalResources.current
    return when (s) {
        CallStatus.None -> ""
        CallStatus.Dialing -> stringResource(R.string.call_status_dialing)
        CallStatus.Ringing -> stringResource(R.string.call_status_ringing)
        CallStatus.Incoming -> stringResource(R.string.call_status_incoming)
        CallStatus.Connecting -> stringResource(R.string.call_status_connecting)
        CallStatus.Reconnecting -> stringResource(R.string.call_status_reconnecting)
        is CallStatus.Timer -> timerText(s.seconds)
        is CallStatus.Ended -> callLineText(res, s.outcome.code, s.durationS)
        is CallStatus.Notice -> stringResource(if (s.notice == CallNotice.NotAvailable) R.string.call_not_available else R.string.call_offline)
    }
}

@Composable
fun CallScreen(
    ui: CallUi, onAccept: () -> Unit, onDecline: () -> Unit, onHangUp: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.screenGutter), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            if (ui.name.isNotEmpty()) OtterAvatar(ui.name, ui.seed, size = 96.dp)
            Spacer(Modifier.height(Dimens.s24))
            Text(ui.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(Dimens.s8))
            Text(statusText(ui.status), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.weight(2f))
            Row(Modifier.fillMaxWidth().padding(bottom = Dimens.s32), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                when {
                    ui.incoming -> {
                        RoundButton(Icons.Filled.CallEnd, stringResource(R.string.call_decline), HangUpRed, onDecline)
                        RoundButton(Icons.Filled.Call, stringResource(R.string.call_accept), AcceptGreen, onAccept)
                    }
                    ui.inCall -> {
                        FilledIconToggleButton(checked = ui.muted, onCheckedChange = { onMute() }, modifier = Modifier.size(64.dp)) {
                            Icon(if (ui.muted) Icons.Filled.MicOff else Icons.Filled.Mic, stringResource(R.string.call_mute))
                        }
                        RoundButton(Icons.Filled.CallEnd, stringResource(R.string.call_hang_up), HangUpRed, onHangUp)
                        FilledIconToggleButton(checked = ui.speaker, onCheckedChange = { onSpeaker() }, modifier = Modifier.size(64.dp)) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.call_speaker))
                        }
                    }
                    else -> Box(Modifier.size(72.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(72.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
    }
}
