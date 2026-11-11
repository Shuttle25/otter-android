package app.ottermsg.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/** Room that text and buttons next to an illustration always keep. */
val IllustrationReserve = 120.dp

/**
 * Height of the viewport for content inside a vertical scroll, where constraints are unbounded.
 * Provided by scrolling screen scaffolds; [OtterIllustration] uses the smaller of it and its own constraints.
 */
val LocalViewportHeight = compositionLocalOf { Dp.Infinity }

/**
 * Decorative Otter illustration (README_AGENT: Image, never a tinted Icon; Fit, never cropped; no content description
 * because the screen's text says the same). Takes no space when the available height is below [maxSize] + 120 dp
 * (keyboard open, 200 % text), so text and buttons always fit. Place it first in a column that gets the screen height.
 */
@Composable
fun OtterIllustration(@DrawableRes id: Int, maxSize: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val available = if (constraints.hasBoundedHeight) min(maxHeight, LocalViewportHeight.current) else LocalViewportHeight.current
        if (available < maxSize + IllustrationReserve) return@BoxWithConstraints
        Image(painterResource(id), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.size(min(maxSize, maxWidth)))
    }
}
