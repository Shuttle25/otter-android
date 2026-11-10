package app.ottermsg.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.ottermsg.core.ui.theme.Dimens

/** Initials avatar. Decorative: the surrounding row already names the person (guide §12). */
@Composable
fun OtterAvatar(name: String, size: Dp = Dimens.avatarList, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(initials(name), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = (size.value * 0.38f).sp,
            style = MaterialTheme.typography.labelLarge)
    }
}

fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
