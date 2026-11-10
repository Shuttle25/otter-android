package app.ottermsg.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.R

/** Delivery status values (same numbers as the database's MessageStatus). */
object DeliveryStatus { const val PENDING = 0; const val SENT = 1; const val DELIVERED = 2; const val READ = 3; const val FAILED = 4 }

/** Guide §8: clock / ✓ / ✓✓ outline / ✓✓ accent / error. Never colour-only: each has a description. */
@Composable
fun DeliveryStatusIcon(status: Int, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        DeliveryStatus.PENDING -> Icons.Outlined.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryStatus.SENT -> Icons.Outlined.Done to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryStatus.DELIVERED -> Icons.Outlined.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
        DeliveryStatus.READ -> Icons.Outlined.DoneAll to MaterialTheme.colorScheme.primary
        else -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = stringResource(deliveryStatusLabel(status)), tint = tint, modifier = modifier.size(16.dp))
}

fun deliveryStatusLabel(status: Int): Int = when (status) {
    DeliveryStatus.PENDING -> R.string.ui_status_pending
    DeliveryStatus.SENT -> R.string.ui_status_sent
    DeliveryStatus.DELIVERED -> R.string.ui_status_delivered
    DeliveryStatus.READ -> R.string.ui_status_read
    else -> R.string.ui_status_failed
}
