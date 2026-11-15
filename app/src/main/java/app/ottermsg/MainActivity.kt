package app.ottermsg

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.backup.InstallGuard
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.data.settings.Settings
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

    /** A notification tap asks to open this conversation (consumed by AppNavHost). */
    val openConversation = MutableStateFlow<Long?>(null)

    /** «Этот телефон снова главный» (C14): a new install here, then sync again; the next login finishes it. */
    fun reclaim() { viewModelScope.launch { guard.reclaim(); engine.resume() } }

    init { viewModelScope.launch { _hasAccount.value = accounts.load() != null } }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            OtterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val hasAccount by vm.hasAccount.collectAsStateWithLifecycle()
                    // Blank themed surface while the local account loads (no skeleton: it is local and fast).
                    val superseded by vm.superseded.collectAsStateWithLifecycle()
                    if (superseded) SupersededScreen(onReclaim = vm::reclaim) else hasAccount?.let { AppNavHost(it, vm.accounts.account, vm.openConversation) }
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
