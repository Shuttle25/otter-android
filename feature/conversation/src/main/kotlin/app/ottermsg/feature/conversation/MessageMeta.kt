package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.ottermsg.core.ui.components.DeliveryStatusIcon
import app.ottermsg.core.ui.theme.Dimens

/** Time and (for outgoing) status in their own row under the text — never drawn over it (guide §7). */
@Composable
fun MessageMeta(time: String, status: Int?, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.s4), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = MaterialTheme.typography.bodySmall, color = color)
        if (status != null) DeliveryStatusIcon(status)
    }
}
