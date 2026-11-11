package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.theme.Dimens

/** Centered group system event (surfaceVariant), read by TalkBack as plain text. */
@Composable
fun SystemEventChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Dimens.messageListGutter, vertical = Dimens.s8), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 480.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Dimens.s12, vertical = Dimens.s4))
        }
    }
}
