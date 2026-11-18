package app.ottermsg.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → «Оформление» (D124): the stored ThemeMode ordinal (0 as the device, 1 light, 2 dark); MainActivity applies it at once. */
@HiltViewModel
class AppearanceViewModel internal constructor(stored: Flow<Int>, private val save: suspend (Int) -> Unit) : ViewModel() {
    @Inject constructor(settings: Settings) : this(settings.observeThemeMode(), { settings.setThemeMode(it) })

    val mode: StateFlow<Int> = stored.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    fun set(v: Int) { viewModelScope.launch { save(v) } }
}

/** Label of a stored theme mode (also the «Оформление» row's subtitle); unknown values read as «Как на устройстве». */
fun appearanceLabel(mode: Int): Int = when (mode) {
    1 -> R.string.appearance_light
    2 -> R.string.appearance_dark
    else -> R.string.appearance_system
}

@Composable
fun AppearanceRoute(onBack: () -> Unit, vm: AppearanceViewModel = hiltViewModel()) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_appearance), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) { AppearanceContent(mode, vm::set) }
    }
}

/** Three radio rows: «Как на устройстве», «Светлая», «Тёмная». */
@Composable
fun AppearanceContent(mode: Int, onSelect: (Int) -> Unit) {
    val current = if (mode in 0..2) mode else 0
    Column(Modifier.fillMaxWidth().padding(vertical = Dimens.s8).selectableGroup()) {
        (0..2).forEach { m ->
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin)
                .selectable(selected = current == m, onClick = { onSelect(m) }, role = Role.RadioButton)
                .padding(horizontal = Dimens.screenGutter), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = current == m, onClick = null)
                Spacer(Modifier.width(Dimens.s16))
                Text(stringResource(appearanceLabel(m)), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
