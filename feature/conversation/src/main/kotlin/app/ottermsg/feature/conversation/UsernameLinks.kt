package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import app.ottermsg.core.data.directory.UsernameLookup
import app.ottermsg.core.ui.R as UiR

/** `@name` or `@name#1a2b3c4d` not preceded by a letter, digit, `_` or `@` (so e-mail addresses are skipped). */
internal val usernameRegex = Regex("""(?<![\p{L}\p{N}_@])@[A-Za-z0-9_]{3,32}(?:#[0-9a-fA-F]{8})?(?![\p{L}\p{N}_])""")

internal fun findUsernames(text: String): List<IntRange> = usernameRegex.findAll(text).map { it.range }.toList()

/** The snackbar text for a mention that found nobody (null for a hit). */
internal fun lookupMessage(r: UsernameLookup.Result): Int? = when (r) {
    UsernameLookup.Result.NotFound -> UiR.string.ui_lookup_not_found
    UsernameLookup.Result.Limited -> UiR.string.ui_lookup_limited
    UsernameLookup.Result.OldNode -> UiR.string.ui_lookup_old_node
    UsernameLookup.Result.Offline -> UiR.string.ui_lookup_offline
    is UsernameLookup.Result.Found -> null
}

/** Where a bubble's text was laid out, for the TalkBack buttons drawn outside the bubble's own semantics. */
internal class UsernameSpots {
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var coords by mutableStateOf<LayoutCoordinates?>(null)
}

/**
 * Message text whose `@username` mentions are links (the way NewsBubble draws URLs): a tap calls [onUsername] with the
 * mention as written ("@Vera"). [spots] receives the layout for [UsernameLinkButtons].
 */
@Composable
internal fun UsernameText(
    text: String, ranges: List<IntRange>, style: TextStyle, color: Color, fontStyle: FontStyle, linkColor: Color,
    spots: UsernameSpots, onUsername: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val tap by rememberUpdatedState(onUsername)
    val annotated = remember(text, ranges, linkColor) {
        buildAnnotatedString {
            var at = 0
            for (r in ranges) {
                append(text.substring(at, r.first))
                val name = text.substring(r)
                withLink(LinkAnnotation.Clickable(name, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) {
                    tap(name)
                }) { append(name) }
                at = r.last + 1
            }
            append(text.substring(at))
        }
    }
    Text(annotated, style = style, color = color, fontStyle = fontStyle, onTextLayout = { spots.layout = it },
        modifier = modifier.onGloballyPositioned { spots.coords = it })
}

/**
 * TalkBack-only buttons «Открыть @name» over each mention (no pointer input: taps reach the link annotation). The bubble
 * clears its children's semantics (it reads as one unit), so these live beside it, in [modifier]'s box (matchParentSize).
 */
@Composable
internal fun UsernameLinkButtons(text: String, ranges: List<IntRange>, spots: UsernameSpots, onUsername: (String) -> Unit,
                                 modifier: Modifier = Modifier) {
    var own by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    Box(modifier.onGloballyPositioned { own = it }) {
        val l = spots.layout
        val t = spots.coords
        val o = own
        if (l != null && t != null && o != null && t.isAttached && o.isAttached) {
            val origin = o.localPositionOf(t, Offset.Zero)
            for (r in ranges) {
                if (r.last >= l.layoutInput.text.length) continue
                val name = text.substring(r)
                val b = l.getPathForRange(r.first, r.last + 1).getBounds()
                val label = stringResource(R.string.username_link_label, name)
                Box(Modifier.offset { IntOffset((origin.x + b.left).toInt(), (origin.y + b.top).toInt()) }
                    .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })
                    .semantics(mergeDescendants = true) {
                        contentDescription = label; role = Role.Button
                        onClick { onUsername(name); true }
                    })
            }
        }
    }
}
