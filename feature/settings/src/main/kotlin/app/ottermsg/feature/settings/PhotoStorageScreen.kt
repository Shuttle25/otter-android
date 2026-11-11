package app.ottermsg.feature.settings

import android.Manifest
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.media.GallerySaver
import app.ottermsg.core.data.media.MediaFiles
import app.ottermsg.core.data.media.PhotoLoader
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** "Фото и память" (guide §10, D46/D47): what the screen reads and changes (a fake in tests). */
interface PhotoPrefs {
    val autoDownload: Flow<Int>
    val saveToGallery: Flow<Boolean>
    suspend fun setAutoDownload(v: Int)
    suspend fun setSaveToGallery(v: Boolean)
    fun galleryNeedsPermission(): Boolean
    /** Temporary files (cacheDir) and encrypted originals (filesDir/media), in bytes. */
    suspend fun sizes(): Pair<Long, Long>
    /** Empties cacheDir and the in-memory image cache; never the photos in chats (D24). */
    suspend fun clearCache()
}

@Singleton
class RealPhotoPrefs @Inject constructor(
    private val settings: Settings, private val files: MediaFiles, private val loader: PhotoLoader, private val gallery: GallerySaver,
) : PhotoPrefs {
    override val autoDownload: Flow<Int> = settings.observeInt(Settings.PHOTO_AUTO, Settings.AUTO_WIFI)
    override val saveToGallery: Flow<Boolean> = settings.observe(Settings.PHOTO_GALLERY).map { b -> b?.let { ByteBuffer.wrap(it).int != 0 } ?: false }
    override suspend fun setAutoDownload(v: Int) = settings.setPhotoAutoDownload(v)
    override suspend fun setSaveToGallery(v: Boolean) = settings.setPhotoSaveToGallery(v)
    override fun galleryNeedsPermission() = gallery.needsPermission()
    override suspend fun sizes() = withContext(Dispatchers.IO) { files.cacheBytes() to files.mediaBytes() }
    override suspend fun clearCache() = withContext(Dispatchers.IO) { files.clearCache(); loader.clear() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PhotoPrefsModule {
    @Binds abstract fun bind(p: RealPhotoPrefs): PhotoPrefs
}

@HiltViewModel
class PhotoStorageViewModel @Inject constructor(private val prefs: PhotoPrefs) : ViewModel() {
    val autoDownload: StateFlow<Int> = prefs.autoDownload.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.AUTO_WIFI)
    val saveToGallery: StateFlow<Boolean> = prefs.saveToGallery.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val _sizes = MutableStateFlow(0L to 0L)
    /** (temporary files, photos on this phone) in bytes. */
    val sizes: StateFlow<Pair<Long, Long>> = _sizes

    init { refresh() }

    fun refresh() = viewModelScope.launch { _sizes.value = prefs.sizes() }
    fun setAuto(v: Int) = viewModelScope.launch { prefs.setAutoDownload(v) }
    fun needsPermission() = prefs.galleryNeedsPermission()
    fun setSave(v: Boolean) = viewModelScope.launch { prefs.setSaveToGallery(v) }
    fun clearCache() = viewModelScope.launch { prefs.clearCache(); _sizes.value = prefs.sizes() }
}

@Composable
fun PhotoStorageRoute(onBack: () -> Unit, vm: PhotoStorageViewModel = hiltViewModel()) {
    val auto by vm.autoDownload.collectAsStateWithLifecycle()
    val save by vm.saveToGallery.collectAsStateWithLifecycle()
    val sizes by vm.sizes.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.setSave(true) }
    PhotoStorageScreen(auto, save, sizes, onBack, vm::setAuto,
        onSave = { on -> if (on && vm.needsPermission()) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.setSave(on) },
        onClear = vm::clearCache)
}

@Composable
fun PhotoStorageScreen(
    auto: Int, save: Boolean, sizes: Pair<Long, Long>, onBack: () -> Unit, onAuto: (Int) -> Unit, onSave: (Boolean) -> Unit, onClear: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.settings_photos), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.photos_auto))
            Column(Modifier.selectableGroup()) {
                for ((value, label) in listOf(Settings.AUTO_WIFI to R.string.photos_auto_wifi, Settings.AUTO_ANY to R.string.photos_auto_any,
                    Settings.AUTO_MANUAL to R.string.photos_auto_manual)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowMin)
                        .selectable(selected = auto == value, onClick = { onAuto(value) }, role = Role.RadioButton)
                        .padding(horizontal = Dimens.screenGutter), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = auto == value, onClick = null)
                        Spacer(Modifier.width(Dimens.s16))
                        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin)
                .toggleable(value = save, onValueChange = onSave, role = Role.Switch)
                .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.photos_save), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.photos_save_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = save, onCheckedChange = null)
            }
            Header(stringResource(R.string.photos_cache))
            Row(Modifier.fillMaxWidth().heightIn(min = Dimens.settingsRowWithCaptionMin).padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Formatter.formatShortFileSize(context, sizes.first), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.photos_cache_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClear, modifier = Modifier.heightIn(min = Dimens.minTouch)) { Text(stringResource(R.string.photos_cache_clear)) }
            }
            Header(stringResource(R.string.photos_stored))
            Text(Formatter.formatShortFileSize(context, sizes.second), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
        }
    }
}

@Composable
private fun Header(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Dimens.screenGutter, end = Dimens.screenGutter, top = Dimens.s24, bottom = Dimens.s8)
            .semantics { heading() })
}
