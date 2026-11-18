package app.ottermsg.feature.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.core.ui.theme.PHOTO_SCRIM_ALPHA

/** Height of a photo shown [width] wide: aspect kept, at most 320 dp (center-crop beyond that). */
internal fun photoHeight(p: PhotoUi, width: Dp): Dp {
    if (p.width <= 0 || p.height <= 0) return min(width, Dimens.photoPreviewMaxHeight)
    return min(width * (p.height.toFloat() / p.width), Dimens.photoPreviewMaxHeight)
}

/**
 * The image part of a photo bubble (guide §9): the photo (or its thumbnail while it isn't here), with honest states —
 * upload/download progress, «Загрузить фото», «Не удалось загрузить», «Фото недоступно». Radius 16 dp.
 */
@Composable
fun PhotoContent(localId: Long, p: PhotoUi, width: Dp, onDownload: () -> Unit, modifierInset: Dp = 0.dp,
                 overlay: @Composable () -> Unit = {}) {
    val source = LocalPhotoSource.current
    val density = LocalDensity.current
    val maxPx = with(density) { width.roundToPx() }.coerceAtLeast(64)
    var image by remember(localId) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember(localId) { mutableStateOf(false) }
    var corrupt by remember(localId) { mutableStateOf(false) }
    LaunchedEffect(localId, p.state) {
        if (p.state == PhotoState.Ready || p.state == PhotoState.Uploading) {
            source.load(localId, maxPx)?.let { image = it; full = true; return@LaunchedEffect }
            corrupt = source.corrupt(localId)
        }
        if (!full) image = source.thumb(localId)
    }
    val h = photoHeight(p, width)
    Box(Modifier.padding(modifierInset).width(width).height(h).clip(RoundedCornerShape(Dimens.photoRadius))
        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        image?.let {
            // restored without the file: only the 64 px thumbnail exists, shown blurred (API 31+) under the label
            Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (p.state == PhotoState.NotInBackup) Modifier.blur(12.dp) else Modifier))
        }
        when (p.state) {
            PhotoState.Uploading, PhotoState.Downloading -> Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = PHOTO_SCRIM_ALPHA)), contentAlignment = Alignment.Center) {
                val progress = p.progress
                if (progress != null && progress > 0f) CircularProgressIndicator(progress = { progress }, color = Color.White, modifier = Modifier.size(32.dp))
                else CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            }
            PhotoState.Remote -> FilledTonalButton(onClick = onDownload, colors = photoButtonColors()) { Text(stringResource(R.string.conv_photo_download)) }
            PhotoState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label(stringResource(R.string.conv_photo_failed))
                FilledTonalButton(onClick = onDownload, colors = photoButtonColors(), modifier = Modifier.padding(top = Dimens.s8)) {
                    Text(stringResource(R.string.conv_retry))
                }
            }
            PhotoState.Gone -> Label(stringResource(R.string.conv_photo_gone))
            PhotoState.NotInBackup -> Label(stringResource(R.string.conv_photo_not_in_backup))
            PhotoState.Ready -> if (corrupt) Label(stringResource(R.string.conv_photo_corrupt))
        }
        overlay()
    }
}

/** Otter surface with primary text (not Material's default tonal lavender). */
@Composable
private fun photoButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary)

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = PHOTO_SCRIM_ALPHA)).padding(horizontal = Dimens.s12, vertical = Dimens.s4))
}
