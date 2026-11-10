package app.ottermsg.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.ottermsg.core.ui.theme.Dimens

/** Settings row (guide §5/§10): min 56 dp, 72 dp with a caption; the whole row is one target. */
@Composable
fun OtterSettingsRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = if (subtitle == null) Dimens.settingsRowMin else Dimens.settingsRowWithCaptionMin)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.s16))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
