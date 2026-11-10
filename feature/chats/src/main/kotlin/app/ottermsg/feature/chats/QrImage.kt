package app.ottermsg.feature.chats

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder

fun qrBitmap(text: String, sizePx: Int = 768): Bitmap =
    BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, sizePx, sizePx, mapOf(EncodeHintType.MARGIN to 2))

/** Black-on-white QR (scanners need the contrast in both themes). */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { qrBitmap(text).asImageBitmap() }
    Image(bmp, contentDescription = description, modifier = modifier.size(256.dp))
}
