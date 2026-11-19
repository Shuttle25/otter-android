package app.ottermsg.feature.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.security.AppLock
import app.ottermsg.core.data.security.PinResult
import app.ottermsg.core.ui.components.OtterIllustration
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.components.PinEntry
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Settings → Безопасность → «Блокировка приложения» (D121). [biometricAvailable] and [deviceSecure] (the phone has a
 * screen lock, so «Забыли PIN?» works) are filled in by the screen.
 */
data class AppLockUi(
    val enabled: Boolean = false, val biometric: Boolean = false, val timeoutMs: Long = AppLock.DEFAULT_TIMEOUT_MS,
    val biometricAvailable: Boolean = false, val deviceSecure: Boolean = true,
)

@HiltViewModel
class AppLockViewModel @Inject constructor(private val lock: AppLock) : ViewModel() {
    private val _ui = MutableStateFlow(AppLockUi())
    val ui: StateFlow<AppLockUi> = _ui

    init { viewModelScope.launch { lock.enabled.collect { refresh() } } }

    private suspend fun refresh() {
        _ui.update { it.copy(enabled = lock.enabled.value, biometric = lock.biometric(), timeoutMs = lock.timeoutMs()) }
    }

    /** The current PIN with the lock's backoff (Argon2 off the main thread). */
    suspend fun check(pin: CharArray): PinResult = withContext(Dispatchers.Default) { lock.checkPin(pin) }

    fun enable(pin: String) { viewModelScope.launch { withContext(Dispatchers.Default) { lock.enable(pin.toCharArray(), biometric = false) } } }

    /** [old] was just checked; changePin checks it again (a right PIN resets the count, so this costs no try). */
    fun change(old: String, new: String, done: () -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { lock.changePin(old.toCharArray(), new.toCharArray()) }
            if (r == PinResult.Ok) done()
        }
    }

    fun disable() { viewModelScope.launch { lock.disable() } }
    fun setBiometric(v: Boolean) { viewModelScope.launch { lock.setBiometric(v); refresh() } }
    fun setTimeout(ms: Long) { viewModelScope.launch { lock.setTimeoutMs(ms); refresh() } }
}

@Composable
fun AppLockRoute(onBack: () -> Unit, vm: AppLockViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bioAvailable = remember { DeviceAuth.biometricAvailable(context) }
    // Re-read on resume: the member may come back from Android's settings with a screen lock set.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val deviceSecure = remember(lifecycle) { DeviceAuth.deviceSecure(context) }
    val changed = stringResource(R.string.app_lock_changed)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_app_lock), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            AppLockSetupContent(ui.copy(biometricAvailable = bioAvailable, deviceSecure = deviceSecure), onEnable = { pin, _ -> vm.enable(pin) },
                onBiometric = vm::setBiometric, onTimeout = vm::setTimeout, check = vm::check,
                onChangePin = { old, new -> vm.change(old, new) { Toast.makeText(context, changed, Toast.LENGTH_SHORT).show() } },
                onDisable = vm::disable)
        }
    }
}

private enum class Step { MAIN, NEW_PIN, CHECK_FOR_DISABLE, CHECK_FOR_CHANGE, CHANGED_PIN }

/**
 * Off: explanation + «Включить» → PIN twice; without a phone screen lock a warning comes first (a forgotten PIN then
 * means reinstalling and restoring from the backup), but the lock is not required. On: fingerprint, «Блокировать», «Сменить PIN», «Выключить» (asks the PIN).
 */
@Composable
fun AppLockSetupContent(
    state: AppLockUi, onEnable: (String, Boolean) -> Unit, onBiometric: (Boolean) -> Unit, onTimeout: (Long) -> Unit,
    check: suspend (CharArray) -> PinResult, onChangePin: (old: String, new: String) -> Unit, onDisable: () -> Unit,
) {
    var step by remember { mutableStateOf(Step.MAIN) }
    var old by remember { mutableStateOf("") }
    var warnNoScreenLock by remember { mutableStateOf(false) }
    if (warnNoScreenLock) AlertDialog(onDismissRequest = { warnNoScreenLock = false },
        text = { Text(stringResource(R.string.app_lock_no_screen_lock)) },
        confirmButton = { TextButton(onClick = { warnNoScreenLock = false; step = Step.NEW_PIN }) {
            Text(stringResource(R.string.app_lock_enable_anyway)) } },
        dismissButton = { TextButton(onClick = { warnNoScreenLock = false }) { Text(stringResource(R.string.app_lock_cancel)) } })
    BackHandler(enabled = step != Step.MAIN) { step = Step.MAIN; old = "" }
    val padMod = Modifier.fillMaxWidth().padding(vertical = Dimens.s24)
    when (step) {
        Step.NEW_PIN -> Column(padMod, horizontalAlignment = Alignment.CenterHorizontally) {
            NewPinFlow { onEnable(it, false); step = Step.MAIN }
        }
        Step.CHECK_FOR_DISABLE -> Column(padMod, horizontalAlignment = Alignment.CenterHorizontally) {
            PinCheck(stringResource(R.string.app_lock_current_pin), check, onOk = { step = Step.MAIN; onDisable() })
        }
        Step.CHECK_FOR_CHANGE -> Column(padMod, horizontalAlignment = Alignment.CenterHorizontally) {
            PinCheck(stringResource(R.string.app_lock_current_pin), check, onOk = { old = it; step = Step.CHANGED_PIN })
        }
        Step.CHANGED_PIN -> Column(padMod, horizontalAlignment = Alignment.CenterHorizontally) {
            NewPinFlow { onChangePin(old, it); old = ""; step = Step.MAIN }
        }
        Step.MAIN -> if (!state.enabled) Column(Modifier.fillMaxWidth().padding(Dimens.screenGutter)) {
            Text(stringResource(R.string.app_lock_explain), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Dimens.s24))
            Button(onClick = { if (state.deviceSecure) step = Step.NEW_PIN else warnNoScreenLock = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)) {
                Text(stringResource(R.string.app_lock_enable))
            }
        } else Column(Modifier.fillMaxWidth()) {
            if (state.biometricAvailable) SwitchRow(stringResource(R.string.app_lock_biometric), null, state.biometric, onBiometric)
            Text(stringResource(R.string.app_lock_timeout), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Dimens.s16, bottom = Dimens.s8))
            Column(Modifier.selectableGroup()) {
                AppLock.TIMEOUTS_MS.forEach { ms ->
                    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin)
                        .selectable(selected = state.timeoutMs == ms, onClick = { onTimeout(ms) }, role = Role.RadioButton)
                        .padding(horizontal = Dimens.screenGutter), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.timeoutMs == ms, onClick = null)
                        Spacer(Modifier.width(Dimens.s16))
                        Text(stringResource(timeoutLabel(ms)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(Dimens.s8))
            OtterSettingsRow(stringResource(R.string.app_lock_change), onClick = { step = Step.CHECK_FOR_CHANGE })
            OtterSettingsRow(stringResource(R.string.app_lock_disable), onClick = { step = Step.CHECK_FOR_DISABLE })
        }
    }
}

fun timeoutLabel(ms: Long): Int = when {
    ms <= 0 -> R.string.app_lock_t0
    ms <= 60_000 -> R.string.app_lock_t1
    ms <= 5 * 60_000 -> R.string.app_lock_t5
    else -> R.string.app_lock_t30
}

/** «Придумайте PIN» → «Повторите PIN»; a mismatch starts over with «PIN-коды не совпадают». */
@Composable
private fun NewPinFlow(onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    PinEntry(
        title = stringResource(if (first == null) R.string.app_lock_new_pin else R.string.app_lock_repeat_pin),
        message = if (mismatch) stringResource(R.string.app_lock_mismatch) else null,
        resetKey = first,
        onSubmit = { p ->
            val f = first
            when {
                f == null -> { first = p; mismatch = false }
                f == p -> { first = null; onDone(p) }
                else -> { first = null; mismatch = true }
            }
        },
    )
}

/**
 * Asks the current PIN through [check] (AppLock.tryPin on the lock screen, AppLock.checkPin in Settings and before the
 * recovery phrase), shows «Неверный PIN. Осталось попыток: N» / «Повторите через m:ss» (ticking), and calls [onOk]
 * with the PIN once it is right. The pad is disabled while a check runs and during a wait.
 */
@Composable
fun PinCheck(title: String, check: suspend (CharArray) -> PinResult, onOk: (String) -> Unit,
             now: () -> Long = System::currentTimeMillis, pending: (suspend () -> PinResult.Wait?)? = null) {
    var result by remember { mutableStateOf<PinResult?>(null) }
    // A wait that started before this screen (e.g. before a force-stop): show its countdown right away.
    if (pending != null) LaunchedEffect(Unit) { pending()?.let { if (result == null) result = it } }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableLongStateOf(now()) }
    val scope = rememberCoroutineScope()
    val r = result
    if (r is PinResult.Wait) LaunchedEffect(r) {
        tick = now()
        while (tick < r.untilMs) { delay(1000 - (r.untilMs - tick) % 1000); tick = now() }
        result = null
    }
    val wrong = stringResource(R.string.app_lock_wrong, (r as? PinResult.Wrong)?.triesLeft ?: 0)
    val waitFmt = stringResource(R.string.app_lock_wait)
    val message = pinMessageText(r, tick, wrong = { wrong }, wait = { String.format(waitFmt, it) })
    PinEntry(title, message, enabled = !busy && r !is PinResult.Wait, onSubmit = { p ->
        busy = true
        scope.launch {
            val res = try { check(p.toCharArray()) } finally { busy = false }
            if (res == PinResult.Ok) { result = null; onOk(p) } else result = res
        }
    })
}

/** The message line for a check result; null for none or Ok. */
fun pinMessageText(r: PinResult?, now: Long, wrong: (Int) -> String, wait: (String) -> String): String? = when (r) {
    is PinResult.Wrong -> wrong(r.triesLeft)
    is PinResult.Wait -> wait(waitText(r.untilMs - now))
    else -> null
}

/** m:ss, rounded up to the next second. */
fun waitText(ms: Long): String {
    val s = ((ms.coerceAtLeast(0) + 999) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * The lock screen's content (D121): Otter, «Введите PIN» with the pad, the fingerprint key when [biometric], and
 * «Забыли PIN?». The activity wiring (BiometricPrompt, device credential) is the caller's.
 */
@Composable
fun AppUnlockContent(check: suspend (CharArray) -> PinResult, biometric: Boolean, onBiometric: () -> Unit, onForgot: () -> Unit,
                     pending: (suspend () -> PinResult.Wait?)? = null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(vertical = Dimens.s24),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        OtterIllustration(app.ottermsg.core.ui.R.drawable.otter_welcome, maxSize = 96.dp)
        Spacer(Modifier.height(Dimens.s16))
        PinCheck(stringResource(R.string.app_lock_title), check, onOk = {}, pending = pending)
        Spacer(Modifier.height(Dimens.s16))
        if (biometric) OutlinedButton(onClick = onBiometric, modifier = Modifier.heightIn(min = Dimens.minTouch)) {
            Icon(Icons.Outlined.Fingerprint, contentDescription = null)
            Spacer(Modifier.width(Dimens.s8))
            Text(stringResource(R.string.app_lock_use_biometric))
        }
        TextButton(onClick = onForgot, modifier = Modifier.heightIn(min = Dimens.minTouch)) {
            Text(stringResource(R.string.app_lock_forgot), textAlign = TextAlign.Center)
        }
    }
}

/** Settings → Безопасность: «Блокировка приложения» (opens its screen) and the «Запрет скриншотов» switch. */
@Composable
internal fun SecurityRows(appLockOn: Boolean, onAppLock: () -> Unit, noScreenshots: Boolean, onNoScreenshots: (Boolean) -> Unit) {
    OtterSettingsRow(stringResource(R.string.settings_app_lock),
        subtitle = stringResource(if (appLockOn) R.string.settings_app_lock_on else R.string.settings_app_lock_off),
        icon = Icons.Outlined.Lock, onClick = onAppLock)
    SwitchRow(stringResource(R.string.settings_no_screenshots), stringResource(R.string.settings_no_screenshots_sub),
        noScreenshots, onNoScreenshots, icon = Icons.Outlined.VisibilityOff)
}

@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) Dimens.settingsRowMin else Dimens.settingsRowWithCaptionMin)
        .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
        .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
        // In the main Settings list every row has an icon, so the titles line up (owner 2026-11-18).
        if (icon != null) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(Dimens.s16)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
