package app.ottermsg.feature.conversation

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ottermsg.core.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

const val PHOTO_PREVIEW_TAG = "photo_preview"

/** Full-screen preview before sending (guide §9): the photo fit, a caption (prefilled with the draft), Cancel / Send. */
@Composable
fun PhotoPreview(path: String, draft: String, sending: Boolean, onCancel: () -> Unit, onSend: (String) -> Unit) {
    var caption by remember(path) { mutableStateOf(draft) }
    var image by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        image = withContext(Dispatchers.IO) {
            runCatching {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, b)
                var s = 1
                while (max(b.outWidth, b.outHeight) / (s * 2) >= 2048) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().imePadding().testTag(PHOTO_PREVIEW_TAG)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(Dimens.s8)) {
            TextField(value = caption, onValueChange = { caption = it }, minLines = 1, maxLines = 5,
                placeholder = { Text(stringResource(R.string.conv_photo_caption)) },
                shape = RoundedCornerShape(Dimens.inputRadius), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = Dimens.minTouch)) { Text(stringResource(R.string.conv_photo_cancel)) }
                Button(onClick = { onSend(caption) }, enabled = !sending, modifier = Modifier.heightIn(min = Dimens.minTouch)) {
                    Text(stringResource(R.string.conv_photo_send))
                }
            }
        }
    }
}
