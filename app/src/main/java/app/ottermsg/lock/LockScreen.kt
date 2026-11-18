package app.ottermsg.lock

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.ottermsg.core.data.security.AppLock
import app.ottermsg.feature.settings.AppUnlockContent
import app.ottermsg.feature.settings.DeviceAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.ottermsg.feature.settings.R as SR

/**
 * The app lock (D121): shown by MainActivity instead of the chats while [AppLock.locked]. The PIN goes through
 * [AppLock.tryPin] (backoff, Argon2 off the main thread); the fingerprint is offered once per lock when enabled;
 * «Забыли PIN?» asks for the phone's own screen lock and then turns the app lock off. Calls (CallActivity) are not
 * gated and do not lead here.
 */
@Composable
fun LockScreen(lock: AppLock, activity: FragmentActivity) {
    var bio by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { bio = lock.biometric() && DeviceAuth.biometricAvailable(activity) }
    val title = stringResource(SR.string.app_lock_unlock_title)
    val usePin = stringResource(SR.string.app_lock_use_pin)
    val confirm = stringResource(SR.string.app_lock_confirm_device)
    val turnedOff = stringResource(SR.string.app_lock_turned_off)
    val noDeviceLock = stringResource(SR.string.app_lock_no_device_lock)
    val scope = rememberCoroutineScope()
    val promptBiometric = { DeviceAuth.biometric(activity, title, usePin) { lock.unlockedByDevice() } }
    // BiometricPrompt cannot start while the activity is stopped (e.g. «Сразу» locks in the background): wait for RESUMED.
    var offered by remember { mutableStateOf(false) }
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(bio, state) {
        if (bio && !offered && state.isAtLeast(Lifecycle.State.RESUMED)) { offered = true; promptBiometric() }
    }
    AppUnlockContent(
        check = { pin -> withContext(Dispatchers.Default) { lock.tryPin(pin) } },
        pending = { lock.currentWait() },
        biometric = bio, onBiometric = promptBiometric,
        onForgot = {
            DeviceAuth.deviceCredential(activity, confirm,
                onOk = { scope.launch { lock.disable(); Toast.makeText(activity, turnedOff, Toast.LENGTH_LONG).show() } },
                onUnavailable = { Toast.makeText(activity, noDeviceLock, Toast.LENGTH_LONG).show() })
        },
    )
}
