package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.theme.Dimens

/**
 * A call line (D80): phone icon + «Исходящий звонок · 3:12». Aligned like a bubble of the calling side; a tap calls
 * the contact back.
 */
@Composable
fun CallLineChip(text: String, outgoing: Boolean, missed: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.messageListGutter, vertical = Dimens.s4),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 480.dp)) {
            Row(Modifier.padding(horizontal = Dimens.s12, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                val color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(Icons.Outlined.Call, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}
