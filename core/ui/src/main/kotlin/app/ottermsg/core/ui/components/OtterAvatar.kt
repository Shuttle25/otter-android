package app.ottermsg.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.ottermsg.core.ui.R
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.core.ui.theme.OtterTheme

/** Background of the family-group art circle: the launcher mint (#DCF1E3) in both themes. */
private val FamilyCircle = Color(0xFFDCF1E3)

/**
 * Avatar (D34). [family] groups show the Otter family art fitted inside a mint circle (never cropped);
 * [news] (the «Новости Выдры» chat, D64) shows the app-icon otter, full-bleed inside the circle;
 * everyone else gets coloured initials whose colour comes from [seed] (user id / group id), so it survives renames.
 * Decorative: the surrounding row already names the person or group (guide §12).
 */
@Composable
fun OtterAvatar(name: String, seed: ByteArray, size: Dp = Dimens.avatarList, modifier: Modifier = Modifier, family: Boolean = false,
    news: Boolean = false) {
    if (news) {
        Image(painterResource(R.drawable.otter_news_avatar), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape).clearAndSetSemantics {})
        return
    }
    if (family) {
        Box(modifier.size(size).clip(CircleShape).background(FamilyCircle).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.otter_family_avatar), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size * 0.06f))
        }
        return
    }
    val c = AvatarPalette.colorsFor(seed, OtterTheme.extraColors.dark)
    Box(
        modifier.size(size).background(Color(c.background), CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = Color(c.text), fontSize = (size.value * 0.38f).sp, style = MaterialTheme.typography.labelLarge)
    }
}

fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
