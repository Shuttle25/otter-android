package app.ottermsg.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.ottermsg.core.ui.theme.Dimens

/**
 * Pure placement used by [AnchoredMenu]; exposed for tests (D38).
 * Above the anchor (gap below the menu), aligned to the anchor's end or start, clamped to the window;
 * below the anchor when there is no room above; at the top of the window when neither fits.
 */
fun anchoredMenuOffset(anchor: IntRect, window: IntSize, popup: IntSize, alignEnd: Boolean, gapPx: Int): IntOffset {
    val maxX = (window.width - popup.width).coerceAtLeast(0)
    val x = (if (alignEnd) anchor.right - popup.width else anchor.left).coerceIn(0, maxX)
    val maxY = (window.height - popup.height).coerceAtLeast(0)
    val above = anchor.top - gapPx - popup.height
    val below = anchor.bottom + gapPx
    val y = when {
        above >= 0 -> above
        below <= maxY -> below
        else -> 0
    }
    return IntOffset(x, y.coerceIn(0, maxY))
}

private class AboveAnchorProvider(private val alignEndLtr: Boolean, private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        anchoredMenuOffset(anchorBounds, windowSize, popupContentSize,
            alignEnd = if (layoutDirection == LayoutDirection.Ltr) alignEndLtr else !alignEndLtr, gapPx = gapPx)
}

data class MenuAction(val label: String, val onClick: () -> Unit)

/** Place inside the composable it should anchor to (e.g. a Box wrapping the bubble Surface). */
@Composable
fun AnchoredMenu(expanded: Boolean, alignEnd: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit) {
    if (!expanded) return
    val gap = with(LocalDensity.current) { Dimens.s8.roundToPx() }
    Popup(
        popupPositionProvider = remember(alignEnd, gap) { AboveAnchorProvider(alignEnd, gap) },
        onDismissRequest = onDismiss, properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(Dimens.s12), tonalElevation = 3.dp, shadowElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp).padding(vertical = Dimens.s4)) {
                actions.forEach { a ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)
                            .clickable { onDismiss(); a.onClick() }.padding(horizontal = Dimens.s16),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(a.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
