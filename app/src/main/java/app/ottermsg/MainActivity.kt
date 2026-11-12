package app.ottermsg

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
import app.ottermsg.core.data.settings.Settings
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
class MainViewModel @Inject constructor(val accounts: AccountRepository, settings: Settings) : ViewModel() {
    /** null while loading; then whether an account exists. */
    private val _hasAccount = MutableStateFlow<Boolean?>(null)
    val hasAccount: StateFlow<Boolean?> = _hasAccount

    /** Another phone restored this account (D52): this one only explains why it stopped. */
    val superseded: StateFlow<Boolean> = settings.observeSuperseded().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init { viewModelScope.launch { _hasAccount.value = accounts.load() != null } }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OtterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val hasAccount by vm.hasAccount.collectAsStateWithLifecycle()
                    // Blank themed surface while the local account loads (no skeleton: it is local and fast).
                    val superseded by vm.superseded.collectAsStateWithLifecycle()
                    if (superseded) SupersededScreen() else hasAccount?.let { AppNavHost(it, vm.accounts.account) }
                }
            }
        }
    }
}
