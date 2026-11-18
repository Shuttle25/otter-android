package app.ottermsg.feature.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import app.ottermsg.core.crypto.Bip39
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.security.AppLock
import app.ottermsg.core.data.security.PinResult
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.components.SecureWindow
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Settings → Безопасность → «Фраза восстановления» (D124). Holds no words: [words] derives them from the account seed
 * on request (after the screen lock), and the screen keeps them only in composition state.
 */
@HiltViewModel
class RecoveryPhraseViewModel internal constructor(
    private val seed: () -> ByteArray, val appLockOn: StateFlow<Boolean>, private val checkPin: suspend (CharArray) -> PinResult,
) : ViewModel() {
    @Inject constructor(accounts: AccountRepository, lock: AppLock) :
        this({ accounts.require().seed }, lock.enabled, { lock.checkPin(it) })

    /** The 24 words; call only after the phone's screen lock or the app-lock PIN said yes. Never stored or logged. */
    fun words(): List<String> = Bip39.toMnemonic(seed())

    /** The app-lock PIN with its backoff (Argon2 off the main thread). */
    suspend fun check(pin: CharArray): PinResult = withContext(Dispatchers.Default) { checkPin(pin) }
}

@Composable
fun RecoveryPhraseRoute(onBack: () -> Unit, vm: RecoveryPhraseViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    // Re-read on resume: the member may come back from Android's settings with a screen lock set.
    val deviceSecure = remember(lifecycle) { DeviceAuth.deviceSecure(context) }
    val lockOn by vm.appLockOn.collectAsStateWithLifecycle()
    // remember, not rememberSaveable: the words never reach saved state; leaving the screen drops them.
    var words by remember { mutableStateOf<List<String>?>(null) }
    var pinPad by remember { mutableStateOf(false) }
    // Hidden again as soon as Otter leaves the screen (home, recents, phone locked).
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) words = null }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val title = stringResource(R.string.phrase_auth_title)
    val ask: () -> Unit = {
        val activity = context.fragmentActivity()
        if (deviceSecure && activity != null) {
            DeviceAuth.deviceCredential(activity, title, onOk = { words = vm.words() }, onUnavailable = { if (lockOn) pinPad = true })
        } else if (lockOn) pinPad = true
    }
    LaunchedEffect(Unit) { if (deviceSecure || lockOn) ask() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_phrase), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            RecoveryPhraseContent(words, canAuthenticate = deviceSecure || lockOn, pinPad = pinPad,
                check = vm::check, onPinOk = { words = vm.words(); pinPad = false }, onAuthenticate = ask)
        }
    }
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

/**
 * [words] shown: the warning and two numbered columns (1–12, 13–24) under [SecureWindow]. Otherwise: without any lock
 * ([canAuthenticate] false) only «Сначала включите блокировку…»; with [pinPad] the app-lock PIN check; else the
 * warning and «Показать слова» ([onAuthenticate] asks the phone's screen lock again).
 */
@Composable
fun RecoveryPhraseContent(
    words: List<String>?, canAuthenticate: Boolean, pinPad: Boolean,
    check: suspend (CharArray) -> PinResult, onPinOk: () -> Unit, onAuthenticate: () -> Unit,
) {
    val text = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s16)
    when {
        words != null -> {
            SecureWindow()
            Text(stringResource(R.string.phrase_warning), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error, modifier = text)
            val half = (words.size + 1) / 2
            Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8),
                horizontalArrangement = Arrangement.spacedBy(Dimens.s16)) {
                listOf(0 until half, half until words.size).forEach { range ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                        range.forEach { i -> Text("${i + 1}. ${words[i]}", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
        !canAuthenticate -> Text(stringResource(R.string.phrase_need_lock), style = MaterialTheme.typography.bodyLarge, modifier = text)
        pinPad -> Column(Modifier.fillMaxWidth().padding(vertical = Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally) {
            PinCheck(stringResource(R.string.phrase_enter_pin), check, onOk = { onPinOk() })
        }
        else -> Column(text) {
            Text(stringResource(R.string.phrase_warning), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Dimens.s24))
            Button(onClick = onAuthenticate, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)) {
                Text(stringResource(R.string.phrase_show))
            }
        }
    }
}
