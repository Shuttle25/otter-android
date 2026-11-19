package app.ottermsg

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.backup.InstallGuard
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.data.security.AppLock
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.ui.components.SecureWindow
import app.ottermsg.core.ui.theme.ThemeMode
import app.ottermsg.lock.LockScreen
import app.ottermsg.notify.MessageNotifier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import app.ottermsg.core.ui.theme.OtterTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val accounts: AccountRepository, settings: Settings, private val guard: InstallGuard, private val engine: SyncEngine,
) : ViewModel() {
    /** null while loading; then whether an account exists. */
    private val _hasAccount = MutableStateFlow<Boolean?>(null)
    val hasAccount: StateFlow<Boolean?> = _hasAccount

    /** Another phone restored this account (D52): this one only explains why it stopped. */
    val superseded: StateFlow<Boolean> = settings.observeSuperseded().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** The network administrator disabled this account (verified, C2): the app only says so. */
    val revoked: StateFlow<Boolean> = settings.observeAccountRevoked().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** A node sent a manifest of another network (D139): «Сеть изменилась» until dismissed; in memory only. */
    val networkChanged: StateFlow<Boolean> = engine.networkChanged
    fun dismissNetworkChanged() = engine.dismissNetworkChanged()

    /** «Запрет скриншотов» (D121): FLAG_SECURE on this window while on. */
    val screenSecure: StateFlow<Boolean> = settings.observeScreenSecure().stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** «Оформление» (D124): ThemeMode ordinal; 0 = as the system. */
    val themeMode: StateFlow<Int> = settings.observeThemeMode().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** A notification tap asks to open this conversation (consumed by AppNavHost). */
    val openConversation = MutableStateFlow<Long?>(null)

    /** «Этот телефон снова главный» (C14): a new install here, then sync again; the next login finishes it. */
    fun reclaim() { viewModelScope.launch { guard.reclaim(); engine.resume() } }

    init { viewModelScope.launch { _hasAccount.value = accounts.load() != null } }
}

/** A FragmentActivity because BiometricPrompt needs one (app lock, D121). */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    @Inject lateinit var appLock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(vm.themeMode.value)
        handle(intent)
        setContent {
            val theme by vm.themeMode.collectAsStateWithLifecycle()
            // Bar icons follow a forced light/dark theme too (D124), and change with it at once.
            LaunchedEffect(theme) { applySystemBars(theme) }
            OtterTheme(ThemeMode.entries.getOrElse(theme) { ThemeMode.SYSTEM }) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val hasAccount by vm.hasAccount.collectAsStateWithLifecycle()
                    // Blank themed surface while the local account and the lock state load (local and fast).
                    val superseded by vm.superseded.collectAsStateWithLifecycle()
                    val revoked by vm.revoked.collectAsStateWithLifecycle()
                    val networkChanged by vm.networkChanged.collectAsStateWithLifecycle()
                    val lockReady by appLock.initialized.collectAsStateWithLifecycle()
                    val locked by appLock.locked.collectAsStateWithLifecycle()
                    val lockOn by appLock.enabled.collectAsStateWithLifecycle()
                    val noShots by vm.screenSecure.collectAsStateWithLifecycle()
                    // Recents: API 33+ hides the preview while the lock is on; older phones only have FLAG_SECURE.
                    SecureWindow(enabled = noShots || (lockOn && Build.VERSION.SDK_INT < 33))
                    LaunchedEffect(lockOn) { if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!lockOn) }
                    // Keeps the chat screens' state (back stack, scroll) while the lock screen covers them.
                    val saved = rememberSaveableStateHolder()
                    when {
                        !lockReady -> Unit
                        locked -> LockScreen(appLock, this@MainActivity) // before «Этот телефон снова главный» too
                        networkChanged -> NetworkChangedScreen(onDismiss = vm::dismissNetworkChanged)
                        revoked -> RevokedScreen()
                        superseded -> SupersededScreen(onReclaim = vm::reclaim)
                        else -> hasAccount?.let { has ->
                            saved.SaveableStateProvider("app") { AppNavHost(has, vm.accounts.account, vm.openConversation) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Notification tap (D62): cold start (onCreate) or warm (onNewIntent). */
    private fun handle(intent: Intent?) {
        if (intent?.action != MessageNotifier.ACTION_OPEN) return
        intent.getLongExtra(MessageNotifier.EXTRA_CONVERSATION, -1).takeIf { it > 0 }?.let { vm.openConversation.value = it }
    }
}
