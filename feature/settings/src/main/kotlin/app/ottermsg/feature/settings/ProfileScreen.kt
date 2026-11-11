package app.ottermsg.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

/** Profile (guide §10): avatar, name, username; the name opens Edit name. */
@Composable
fun ProfileRoute(onBack: () -> Unit, onEditName: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    ProfileScreen(profile, pending, onBack, onEditName)
}

@Composable
fun ProfileScreen(me: ProfileInfo?, pending: Boolean, onBack: () -> Unit, onEditName: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.profile_title), onBack = onBack) }) { padding ->
        if (me == null) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Dimens.s24))
            OtterAvatar(me.displayName, seed = me.userId, size = Dimens.avatarProfile)
            Spacer(Modifier.height(Dimens.s12))
            Text(me.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Dimens.screenGutter))
            Text("@${me.username}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pending) {
                Text(stringResource(R.string.profile_pending), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8))
            }
            Spacer(Modifier.height(Dimens.s24))
            OtterSettingsRow(stringResource(R.string.profile_name), subtitle = me.displayName, icon = Icons.Outlined.Badge,
                onClick = onEditName)
            OtterSettingsRow(stringResource(R.string.profile_username),
                subtitle = "@${me.username} · ${stringResource(R.string.profile_username_fixed)}", icon = Icons.Outlined.AlternateEmail)
        }
    }
}
