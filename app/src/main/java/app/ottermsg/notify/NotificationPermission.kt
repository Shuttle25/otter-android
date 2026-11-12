package app.ottermsg.notify

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.ottermsg.core.data.settings.Settings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** D62: POST_NOTIFICATIONS is asked once (Android 13+), the first time the chat list is shown. */
@Composable
fun NotificationPermissionOnce(vm: PermissionViewModel = hiltViewModel()) {
    if (Build.VERSION.SDK_INT < 33) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (vm.shouldAsk()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

@HiltViewModel
class PermissionViewModel @Inject constructor(private val settings: Settings) : ViewModel() {
    /** True exactly once per install. */
    suspend fun shouldAsk(): Boolean = !settings.notificationPermissionAsked().also { settings.setNotificationPermissionAsked() }
}
