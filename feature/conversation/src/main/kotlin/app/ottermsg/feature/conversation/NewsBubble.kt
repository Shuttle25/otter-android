package app.ottermsg.feature.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.ottermsg.core.data.news.NewsText
import app.ottermsg.core.ui.theme.Dimens

internal val urlRegex = Regex("""https?://[^\s<>"«»]+""", RegexOption.IGNORE_CASE)

/** URL ranges in [text]; trailing punctuation is not part of a URL. */
internal fun findLinks(text: String): List<IntRange> = urlRegex.findAll(text).map { m ->
    var end = m.range.last
    while (end > m.range.first && text[end] in ".,;:!?)]}'") end--
    m.range.first..end
}.toList()

/** The text of a news message in [language] (picked at display time, so a language change re-renders it, D69). */
internal fun newsText(m: MessageUi, language: String): String =
    m.news?.let { NewsText.pick(it.textRu, it.textEn, language) } ?: m.text

private val AuthorStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

/**
 * A message from «Выдра» / "Otter" in the news chat (D64): an incoming bubble whose http/https links open only after
 * «Открыть ссылку?» shows the full address (D69). [slot] holds the «Обновить» row of an announcement with an update.
 * TalkBack: the bubble reads sender, text and time; each link is its own button «Ссылка: <url>».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewsBubble(
    m: MessageUi, text: String, firstInGroup: Boolean,
    onLongPress: () -> Unit = {},
    actions: List<CustomAccessibilityAction> = emptyList(),
    menu: @Composable () -> Unit = {},
    slot: @Composable () -> Unit = {},
) {
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val sender = stringResource(R.string.news_sender)
    val time = timeOf(m.sentMs)
    val ranges = remember(text) { findLinks(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            var at = 0
            for (r in ranges) {
                append(text.substring(at, r.first))
                val url = text.substring(r)
                withLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) {
                    pendingUrl = url
                }) { append(url) }
                at = r.last + 1
            }
            append(text.substring(at))
        }
    }
    val large = LocalDensity.current.fontScale >= 1.3f
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Dimens.messageListGutter)
        .padding(top = if (firstInGroup) Dimens.groupGap else Dimens.sameAuthorGap)) {
        val maxW = min(maxWidth * (if (large) Dimens.bubbleMaxFractionLargeFont else Dimens.bubbleMaxFraction), Dimens.bubbleMaxWidth)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Box {
                Surface(
                    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(Dimens.bubbleRadius),
                    modifier = Modifier.widthIn(max = maxW)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress)
                        .semantics { customActions = actions },
                ) {
                    Column(Modifier.padding(horizontal = Dimens.s12, vertical = Dimens.s8)) {
                        if (firstInGroup) Text(sender, style = AuthorStyle, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clearAndSetSemantics {})
                        Box {
                            Text(annotated, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                                onTextLayout = { layout = it },
                                modifier = Modifier.clearAndSetSemantics { contentDescription = "$sender, $text, $time" })
                            // TalkBack-only buttons over each link (no pointer input: taps reach the link annotation).
                            layout?.let { l ->
                                for (r in ranges) {
                                    if (r.last >= l.layoutInput.text.length) continue
                                    val url = text.substring(r)
                                    val b: Rect = l.getPathForRange(r.first, r.last + 1).getBounds()
                                    val label = stringResource(R.string.news_link_label, url)
                                    Box(Modifier.offset { IntOffset(b.left.toInt(), b.top.toInt()) }
                                        .size(with(density) { b.width.toDp() }, with(density) { b.height.toDp() })
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = label; role = Role.Button
                                            onClick { pendingUrl = url; true }
                                        })
                                }
                            }
                        }
                        Box(Modifier.align(androidx.compose.ui.Alignment.End).clearAndSetSemantics {}) {
                            MessageMeta(time, null, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        slot()
                    }
                }
                menu()
            }
        }
    }
    pendingUrl?.let { url ->
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text(stringResource(R.string.news_link_title)) },
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = { pendingUrl = null; runCatching { uriHandler.openUri(url) } }) {
                    Text(stringResource(R.string.news_link_open))
                }
            },
            dismissButton = { TextButton(onClick = { pendingUrl = null }) { Text(stringResource(R.string.news_link_cancel)) } },
        )
    }
}
