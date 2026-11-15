package app.ottermsg.feature.conversation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.media.PhotoStore
import app.ottermsg.core.ui.theme.Dimens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(private val photos: PhotoStore, savedState: SavedStateHandle) : ViewModel() {
    val localId: Long = checkNotNull(savedState.get<Long>(ARG_LOCAL_ID)) { "localId missing" }
    private val _image = MutableStateFlow<ImageBitmap?>(null)
    val image: StateFlow<ImageBitmap?> = _image
    private val _corrupt = MutableStateFlow(false)
    /** The photo is here but cannot be decoded safely (C21): «Фото повреждено» instead of a spinner. */
    val corrupt: StateFlow<Boolean> = _corrupt
    private val _events = MutableSharedFlow<PhotoEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<PhotoEvent> = _events

    init {
        viewModelScope.launch {
            val b = photos.load(localId, MAX_PX)
            _image.value = b?.asImageBitmap()
            if (b == null) _corrupt.value = photos.corrupt(localId)
        }
    }

    fun needsPermission() = photos.galleryNeedsPermission()
    fun save() = viewModelScope.launch { _events.tryEmit(if (photos.saveToGallery(localId)) PhotoEvent.Saved else PhotoEvent.SaveFailed) }

    companion object {
        const val ARG_LOCAL_ID = "localId"
        const val MAX_PX = 4096
    }
}

/** Full screen on black in both themes; pinch 1–5×, pan clamped, double tap 1×/2.5× (guide §9). */
@Composable
fun PhotoViewerRoute(onBack: () -> Unit, vm: PhotoViewerViewModel = hiltViewModel()) {
    val image by vm.image.collectAsStateWithLifecycle()
    val corrupt by vm.corrupt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(R.string.conv_photo_saved)
    val failed = stringResource(R.string.conv_photo_save_failed)
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(if (it == PhotoEvent.Saved) saved else failed) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.save() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        image?.let { ZoomableImage(it) } ?: if (corrupt) Text(stringResource(R.string.conv_photo_corrupt), color = Color.White,
            modifier = Modifier.align(Alignment.Center)) else CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = .35f)), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.viewer_back), tint = Color.White)
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = { if (vm.needsPermission()) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.save() },
                enabled = image != null) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.conv_photo_save), tint = Color.White)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ZoomableImage(image: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(image, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    val maxX = (size.width * (scale - 1)) / 2
                    val maxY = (size.height * (scale - 1)) / 2
                    offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
}
