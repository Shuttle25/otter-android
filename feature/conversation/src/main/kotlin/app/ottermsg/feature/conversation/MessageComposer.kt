package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.ottermsg.core.ui.theme.Dimens

/** Growing 1–5 line field (Enter = new line) and a round 48 dp send button, disabled when blank or while sending. */
@Composable
fun MessageComposer(text: String, sending: Boolean, onText: (String) -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = Dimens.s8, vertical = Dimens.s8),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
        TextField(
            value = text, onValueChange = onText,
            placeholder = { Text(stringResource(R.string.conv_message_hint)) },
            minLines = 1, maxLines = 5,
            shape = RoundedCornerShape(Dimens.inputRadius),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(onClick = onSend, enabled = text.isNotBlank() && !sending, shape = CircleShape,
            modifier = Modifier.size(Dimens.minTouch)) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.conv_send))
        }
    }
}
