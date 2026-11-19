package app.ottermsg.feature.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.theme.Dimens

/** A person found by `@username` (D137): avatar, name, `@username` and «Написать» (or «Открыть чат»). */
@Composable
fun PersonCard(displayName: String, username: String, userId: ByteArray, hasChat: Boolean, onWrite: () -> Unit,
               modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.screenGutter, vertical = Dimens.s16),
        horizontalAlignment = Alignment.CenterHorizontally) {
        OtterAvatar(displayName, userId, size = Dimens.avatarProfile)
        Spacer(Modifier.height(Dimens.s12))
        Text(displayName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (username.isNotEmpty()) Text("@$username", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s24))
        Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (hasChat) R.string.person_open_chat else R.string.person_write))
        }
    }
}
