package app.ottermsg.feature.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.min
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.components.deliveryStatusLabel
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.core.ui.theme.OtterTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Space between the bubble edge and the photo (the photo keeps its own 16 dp radius). */
private val PHOTO_INSET = 4.dp

/** True on bubbles that show the author line (tests; the bubble is one cleared TalkBack unit). */
val ShowsAuthor = SemanticsPropertyKey<Boolean>("ShowsAuthor")

/** Author line in group bubbles: 14/20 sp, weight 600. */
private val AuthorStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

fun timeOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

/**
 * One message. Outgoing right on outgoingContainer, incoming left on surface; 20 dp radius; 12/8 dp padding;
 * max width min(84 %, 480 dp), 94 % with large fonts. TalkBack reads it as one unit: author, text, time, status.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    m: MessageUi, firstInGroup: Boolean,
    onLongPress: () -> Unit = {}, onTap: () -> Unit = {},
    actions: List<CustomAccessibilityAction> = emptyList(),
    menu: @Composable () -> Unit = {},
    onPhotoDownload: () -> Unit = {},
) {
    val extra = OtterTheme.extraColors
    val container = if (m.system) MaterialTheme.colorScheme.surfaceVariant else if (m.outgoing) extra.outgoingContainer else MaterialTheme.colorScheme.surface
    val onContainer = if (m.system) MaterialTheme.colorScheme.onSurfaceVariant else if (m.outgoing) extra.onOutgoingContainer else MaterialTheme.colorScheme.onSurface
    val text = if (m.system) stringResource(R.string.conv_undecryptable) else m.text
    val time = timeOf(m.sentMs)
    val status = if (m.outgoing) m.status else null
    val author = if (m.outgoing) stringResource(R.string.conv_you) else m.authorName
    val statusLabel = status?.let { stringResource(deliveryStatusLabel(it)) }
    val failedHint = if (status == DeliveryStatus.FAILED) stringResource(R.string.conv_failed_hint) else null
    val photoLabel = when (m.photo?.state) {
        null -> null
        PhotoState.Gone -> stringResource(R.string.conv_photo_gone)
        PhotoState.NotInBackup -> stringResource(R.string.conv_photo_label) + ", " + stringResource(R.string.conv_photo_not_in_backup)
        PhotoState.Remote -> stringResource(R.string.conv_photo_label) + ", " + stringResource(R.string.conv_photo_download)
        PhotoState.Failed -> stringResource(R.string.conv_photo_label) + ", " + stringResource(R.string.conv_photo_failed)
        PhotoState.Downloading -> stringResource(R.string.conv_photo_loading)
        else -> stringResource(R.string.conv_photo_label)
    }
    val description = listOfNotNull(author.ifEmpty { null }, photoLabel, text.ifEmpty { null }, time, statusLabel, failedHint).joinToString(", ")
    val large = LocalDensity.current.fontScale >= 1.3f
    val showAuthor = m.inGroup && firstInGroup && !m.system

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Dimens.messageListGutter)
        .padding(top = if (firstInGroup) Dimens.groupGap else Dimens.sameAuthorGap)) {
        val maxW = min(maxWidth * (if (large) Dimens.bubbleMaxFractionLargeFont else Dimens.bubbleMaxFraction), Dimens.bubbleMaxWidth)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else if (m.system) Arrangement.Center else Arrangement.Start) {
            // The Box hugs the Surface, so a popup placed in [menu] anchors to the bubble itself, not the row.
            Box {
                Surface(
                    color = container, shape = RoundedCornerShape(Dimens.bubbleRadius),
                    modifier = Modifier.widthIn(max = maxW)
                        .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                        .clearAndSetSemantics {
                            contentDescription = description
                            onClick(label = null) { onTap(); true }
                            customActions = actions
                            set(ShowsAuthor, showAuthor)
                        },
                ) {
                    val photo = m.photo
                    if (photo != null) Column {
                        if (showAuthor) Text(m.authorName, style = AuthorStyle, color = onContainer,
                            modifier = Modifier.padding(start = Dimens.s12, end = Dimens.s12, top = Dimens.s8))
                        val metaColor = if (m.outgoing) onContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        PhotoContent(m.localId, photo, maxW - PHOTO_INSET * 2, onDownload = onPhotoDownload,
                            modifierInset = PHOTO_INSET, overlay = {
                                if (text.isEmpty()) Box(Modifier.fillMaxSize().padding(Dimens.s8), contentAlignment = Alignment.BottomEnd) {
                                    Box(Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(Dimens.s8))
                                        .padding(horizontal = Dimens.s8, vertical = 2.dp)) {
                                        MessageMeta(time, status, color = Color.White, onPhoto = true)
                                    }
                                }
                            })
                        if (text.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.s12, vertical = Dimens.s8)) {
                            Text(text, style = MaterialTheme.typography.bodyLarge, color = onContainer)
                            MessageMeta(time, status, color = metaColor, modifier = Modifier.align(Alignment.End))
                        }
                    } else Column(Modifier.padding(horizontal = Dimens.s12, vertical = Dimens.s8)) {
                        // Group: the author's name at the start of a series only (guide §7); same colour for everyone.
                        if (showAuthor) Text(m.authorName, style = AuthorStyle, color = onContainer)
                        Text(text, style = MaterialTheme.typography.bodyLarge, color = onContainer,
                            fontStyle = if (m.system) FontStyle.Italic else FontStyle.Normal)
                        MessageMeta(time, status, color = if (m.outgoing) onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End))
                    }
                }
                menu()
            }
        }
    }
}
