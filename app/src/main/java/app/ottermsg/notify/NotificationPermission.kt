package app.ottermsg.notify

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.ottermsg.R
import app.ottermsg.core.data.settings.Settings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * D62: POST_NOTIFICATIONS is asked once (Android 13+), the first time the chat list is shown.
 * After that prompt is answered (or when there is none), on Android 14+ the member is asked once to allow
 * full-screen notifications, so an incoming call wakes a locked phone. The Settings row stays as the later fix.
 */
@Composable
fun NotificationPermissionOnce(vm: PermissionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    // Saveable: a recreated activity must keep waiting for a POST_NOTIFICATIONS prompt that is still open.
    var notifPending by rememberSaveable { mutableStateOf(false) }
    var notifDone by rememberSaveable { mutableStateOf(false) }
    var showFsi by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifPending = false; notifDone = true
    }
    LaunchedEffect(Unit) {
        if (notifPending || notifDone) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 && vm.shouldAsk()) {
            notifPending = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else notifDone = true
    }
    LaunchedEffect(notifDone) {
        if (!notifDone || showFsi) return@LaunchedEffect
        val canUse = Build.VERSION.SDK_INT < 34 || NotificationManagerCompat.from(context).canUseFullScreenIntent()
        if (vm.shouldAskFullScreenIntent(Build.VERSION.SDK_INT, canUse)) showFsi = true
    }
    if (showFsi) FullScreenIntentDialog(
        onAllow = {
            showFsi = false
            if (Build.VERSION.SDK_INT >= 34) runCatching {
                context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        onLater = { showFsi = false },
    )
}

@Composable
private fun FullScreenIntentDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.fsi_ask_title)) },
        text = { Text(stringResource(R.string.fsi_ask_text)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.fsi_ask_allow)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.fsi_ask_later)) } },
    )
}

/** Pure decision: ask for full-screen notifications only on Android 14+, when not granted and never asked before. */
fun shouldAskFullScreenIntent(sdkInt: Int, canUseFullScreenIntent: Boolean, askedBefore: Boolean): Boolean =
    sdkInt >= 34 && !canUseFullScreenIntent && !askedBefore

/** Applies [shouldAskFullScreenIntent] against the stored flag; the flag is written only when the dialog is shown. */
suspend fun askFullScreenIntentOnce(sdkInt: Int, canUseFullScreenIntent: Boolean,
                                    asked: suspend () -> Boolean, markAsked: suspend () -> Unit): Boolean {
    if (sdkInt < 34 || canUseFullScreenIntent) return false
    return shouldAskFullScreenIntent(sdkInt, canUseFullScreenIntent, asked()).also { if (it) markAsked() }
}

@HiltViewModel
class PermissionViewModel @Inject constructor(private val settings: Settings) : ViewModel() {
    /** True exactly once per install. */
    suspend fun shouldAsk(): Boolean = !settings.notificationPermissionAsked().also { settings.setNotificationPermissionAsked() }

    suspend fun shouldAskFullScreenIntent(sdkInt: Int, canUse: Boolean): Boolean =
        askFullScreenIntentOnce(sdkInt, canUse, settings::fullScreenIntentAsked, settings::setFullScreenIntentAsked)
}
