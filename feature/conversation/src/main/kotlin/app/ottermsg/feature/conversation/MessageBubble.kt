package app.ottermsg.feature.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.min
import app.ottermsg.core.ui.components.DeliveryStatus
import app.ottermsg.core.ui.components.deliveryStatusLabel
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.core.ui.theme.OtterTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val description = listOfNotNull(author.ifEmpty { null }, text, time, statusLabel, failedHint).joinToString(", ")
    val large = LocalDensity.current.fontScale >= 1.3f

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Dimens.messageListGutter)
        .padding(top = if (firstInGroup) Dimens.groupGap else Dimens.sameAuthorGap)) {
        val maxW = min(maxWidth * (if (large) Dimens.bubbleMaxFractionLargeFont else Dimens.bubbleMaxFraction), Dimens.bubbleMaxWidth)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else if (m.system) Arrangement.Center else Arrangement.Start) {
            Surface(
                color = container, shape = RoundedCornerShape(Dimens.bubbleRadius),
                modifier = Modifier.widthIn(max = maxW)
                    .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                    .clearAndSetSemantics {
                        contentDescription = description
                        onClick(label = null) { onTap(); true }
                        customActions = actions
                    },
            ) {
                Column(Modifier.padding(horizontal = Dimens.s12, vertical = Dimens.s8)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = onContainer,
                        fontStyle = if (m.system) FontStyle.Italic else FontStyle.Normal)
                    MessageMeta(time, status, color = if (m.outgoing) onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }
}
