package app.ottermsg.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import app.ottermsg.core.ui.R
import app.ottermsg.core.ui.theme.Dimens

/** 64 dp top bar (guide §5). The title is a TalkBack heading and stays on one line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtterTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            // Avatar, title and subtitle form one target when the title opens details (guide §7: group → info).
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier) {
                leading?.invoke()
                Column {
                    Text(
                        title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = if (subtitle == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.ui_back))
            }
        },
        actions = actions,
        expandedHeight = Dimens.topBarHeight,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

