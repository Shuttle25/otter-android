package app.ottermsg.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.R
import app.ottermsg.core.ui.theme.OtterPalette

/** Delivery status values (same numbers as the database's MessageStatus). */
object DeliveryStatus { const val PENDING = 0; const val SENT = 1; const val DELIVERED = 2; const val READ = 3; const val FAILED = 4 }

/** Test tag of the READ badge. */
const val READ_BADGE_TAG = "delivery-read-badge"

/**
 * Guide §8, design B (owner, 2026-11-15): clock / ✓ / ✓✓ in [ink] (the colour of the time next to them), READ = ✓✓
 * cut out of a filled teal circle (shape, not colour only), error in red. [onPhoto]: on the dark photo scrim, white
 * ticks and the dark palette's badge in both themes. Never colour-only: each has a description.
 */
@Composable
fun DeliveryStatusIcon(
    status: Int,
    modifier: Modifier = Modifier,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    onPhoto: Boolean = false,
) {
    val label = stringResource(deliveryStatusLabel(status))
    if (status == DeliveryStatus.READ) {
        val bg = if (onPhoto) Color(OtterPalette.Dark.primary) else MaterialTheme.colorScheme.primary
        val fg = if (onPhoto) Color(OtterPalette.Dark.onPrimary) else MaterialTheme.colorScheme.onPrimary
        Box(
            modifier.size(16.dp).background(bg, CircleShape).testTag(READ_BADGE_TAG)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.DoneAll, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp)) }
        return
    }
    val icon = when (status) {
        DeliveryStatus.PENDING -> Icons.Outlined.Schedule
        DeliveryStatus.SENT -> Icons.Outlined.Done
        DeliveryStatus.DELIVERED -> Icons.Outlined.DoneAll
        else -> Icons.Outlined.ErrorOutline
    }
    val tint = when {
        icon == Icons.Outlined.ErrorOutline -> MaterialTheme.colorScheme.error
        onPhoto -> Color.White
        else -> ink
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = modifier.size(16.dp))
}

fun deliveryStatusLabel(status: Int): Int = when (status) {
    DeliveryStatus.PENDING -> R.string.ui_status_pending
    DeliveryStatus.SENT -> R.string.ui_status_sent
    DeliveryStatus.DELIVERED -> R.string.ui_status_delivered
    DeliveryStatus.READ -> R.string.ui_status_read
    else -> R.string.ui_status_failed
}
