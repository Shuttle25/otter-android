package app.ottermsg.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.unit.dp
import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.data.backup.BackupState
import app.ottermsg.core.data.backup.PasswordPolicy
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.components.SecureWindow
import app.ottermsg.core.ui.theme.Dimens
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the backup screen reads and does (a fake in tests). */
interface BackupControl {
    val state: Flow<BackupState>
    val online: Flow<Boolean>
    suspend fun setPassword(password: String): Int
    suspend fun runNow(): Int
}

@Singleton
class RealBackupControl @Inject constructor(private val backups: BackupManager, sync: SyncEngine) : BackupControl {
    override val state = backups.state
    override val online = sync.connection.map { it is ConnectionState.Online }
    override suspend fun setPassword(password: String) = backups.setPassword(password)
    override suspend fun runNow() = backups.runNow()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupControlModule {
    @Binds abstract fun bind(c: RealBackupControl): BackupControl
}

@HiltViewModel
class BackupViewModel @Inject constructor(private val backups: BackupControl) : ViewModel() {
    val state: StateFlow<BackupState?> = backups.state.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val online: StateFlow<Boolean> = backups.online.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    data class Form(val open: Boolean = false, val pw: String = "", val repeat: String = "", val busy: Boolean = false)
    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form
    private val _message = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** One-off snackbar texts (string ids). */
    val message: SharedFlow<Int> = _message

    fun openForm() = _form.update { Form(open = true) }
    fun closeForm() = _form.update { if (it.busy) it else Form() }
    fun onPw(v: String) = _form.update { it.copy(pw = v) }
    fun onRepeat(v: String) = _form.update { it.copy(repeat = v) }

    fun submit() {
        val f = _form.value
        if (!canSubmit(f)) return
        _form.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = backups.setPassword(f.pw)
            _form.value = Form()
            _message.tryEmit(messageFor(r))
        }
    }

    fun runNow() {
        viewModelScope.launch { _message.tryEmit(messageFor(backups.runNow())) }
    }

    companion object {
        fun canSubmit(f: Form) = !f.busy && f.pw == f.repeat && PasswordPolicy.check(f.pw) == PasswordPolicy.Result.OK
        fun messageFor(code: Int) = when (code) {
            BackupManager.OK -> R.string.backup_done
            BackupManager.ERR_OFFLINE -> R.string.backup_offline
            BackupManager.ERR_QUOTA -> R.string.backup_quota
            else -> R.string.backup_failed
        }
        fun errorText(code: Int): Int? = if (code == BackupManager.OK || code == BackupManager.ERR_WEAK) null else messageFor(code)
        /** zxcvbn 0–4 → label: 0–2 weak (rejected), 3 fair, 4 strong. */
        fun strengthLabel(score: Int) = when {
            score >= 4 -> R.string.backup_strength_strong
            score == 3 -> R.string.backup_strength_ok
            else -> R.string.backup_strength_weak
        }
    }
}

@Composable
fun BackupRoute(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.message.collect { snackbar.showSnackbar(context.getString(it)) } }
    BackupScreen(state, online, form, snackbar, onBack = { if (form.open) vm.closeForm() else onBack() },
        onOpenForm = vm::openForm, onPw = vm::onPw, onRepeat = vm::onRepeat, onSubmit = vm::submit, onRunNow = vm::runNow)
}

@Composable
fun BackupScreen(
    state: BackupState?, online: Boolean, form: BackupViewModel.Form, snackbar: SnackbarHostState, onBack: () -> Unit,
    onOpenForm: () -> Unit, onPw: (String) -> Unit, onRepeat: (String) -> Unit, onSubmit: () -> Unit, onRunNow: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
        topBar = { OtterTopBar(stringResource(R.string.backup_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s16), verticalArrangement = Arrangement.spacedBy(Dimens.s16)) {
            when {
                form.open -> PasswordForm(form, running = (state as? BackupState.On)?.running == true, onPw, onRepeat, onSubmit)
                state == null -> {}
                state is BackupState.Off -> {
                    Text(stringResource(R.string.backup_off_text), style = MaterialTheme.typography.bodyLarge)
                    PrimaryButton(stringResource(R.string.backup_enable), enabled = true, onClick = onOpenForm)
                }
                state is BackupState.On -> On(state, online, onRunNow, onOpenForm)
            }
        }
    }
}

@Composable
private fun On(state: BackupState.On, online: Boolean, onRunNow: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val last = if (state.lastMs > 0) stringResource(R.string.backup_last,
        DateUtils.formatDateTime(context, state.lastMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
        else stringResource(R.string.backup_never)
    Text(last, style = MaterialTheme.typography.titleMedium)
    BackupViewModel.errorText(state.error)?.let {
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (state.running) Busy(stringResource(R.string.backup_running))
    PrimaryButton(stringResource(R.string.backup_now), enabled = online && !state.running, onClick = onRunNow)
    OutlinedButton(onClick = onChange, enabled = !state.running, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)) {
        Text(stringResource(R.string.backup_change))
    }
}

@Composable
private fun PasswordForm(form: BackupViewModel.Form, running: Boolean, onPw: (String) -> Unit, onRepeat: (String) -> Unit, onSubmit: () -> Unit) {
    SecureWindow()
    var visible by rememberSaveable { mutableStateOf(false) }
    val transform = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val toggle = @Composable {
        IconButton(onClick = { visible = !visible }) {
            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = stringResource(R.string.backup_show))
        }
    }
    Text(stringResource(R.string.backup_rule), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(form.pw, onPw, label = { Text(stringResource(R.string.backup_password)) }, singleLine = true, enabled = !form.busy,
        visualTransformation = transform, trailingIcon = toggle, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth())
    if (form.pw.isNotEmpty()) Strength(PasswordPolicy.score(form.pw))
    OutlinedTextField(form.repeat, onRepeat, label = { Text(stringResource(R.string.backup_password_repeat)) }, singleLine = true,
        enabled = !form.busy, visualTransformation = transform, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = form.repeat.isNotEmpty() && form.repeat != form.pw,
        supportingText = { if (form.repeat.isNotEmpty() && form.repeat != form.pw) Text(stringResource(R.string.backup_mismatch)) },
        modifier = Modifier.fillMaxWidth())
    if (form.busy) Busy(stringResource(if (running) R.string.backup_running else R.string.backup_preparing))
    PrimaryButton(stringResource(R.string.backup_enable), enabled = BackupViewModel.canSubmit(form), onClick = onSubmit)
}

@Composable
private fun Strength(score: Int) {
    val label = stringResource(R.string.backup_strength, stringResource(BackupViewModel.strengthLabel(score)))
    val color = if (score >= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = label }) {
        LinearProgressIndicator(progress = { score / 4f }, color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(Dimens.s8))
        Spacer(Modifier.height(Dimens.s8))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(Dimens.s24), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Spacer(Modifier.width(Dimens.s16))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
        Text(text)
    }
}
