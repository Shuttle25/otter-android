package app.ottermsg.feature.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Share
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.OtterAvatar
import app.ottermsg.core.ui.components.OtterSettingsRow
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

/** Profile (guide §10): avatar, name, username, account code; the name opens Edit name. */
@Composable
fun ProfileRoute(onBack: () -> Unit, onEditName: () -> Unit, vm: ProfileViewModel = hiltViewModel()) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.profile_share_username)
    ProfileScreen(profile, pending, onBack, onEditName, onShareUsername = {
        // «Поделиться именем пользователя» (M9.5): "@name" through the system chooser, so others can find us exactly (D137)
        profile?.let { me ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "@${me.username}")
            runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
        }
    })
}

@Composable
fun ProfileScreen(me: ProfileInfo?, pending: Boolean, onBack: () -> Unit, onEditName: () -> Unit, onShareUsername: () -> Unit = {}) {
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
            OtterSettingsRow(stringResource(R.string.profile_share_username), icon = Icons.Outlined.Share, onClick = onShareUsername)
            AccountCodeRow(accountCode(me.userId))
        }
    }
}

/**
 * «Код аккаунта» (D111): the user id as 32 hex digits in 8 groups of 4, to be read aloud to the admin after a lost
 * phone (`otter-admin user rekey NAME --new CODE` accepts it with or without the spaces).
 */
fun accountCode(userId: ByteArray): String = userId.joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")

/** The account code; a long press copies it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountCodeRow(code: String) {
    val clipboard = LocalClipboardManager.current
    val copy = stringResource(R.string.profile_account_code_copy)
    Row(Modifier.fillMaxWidth()
        .combinedClickable(onClick = {}, onLongClick = { clipboard.setText(AnnotatedString(code)) }, onLongClickLabel = copy)
        .semantics { customActions = listOf(CustomAccessibilityAction(copy) { clipboard.setText(AnnotatedString(code)); true }) }
        .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s8),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.s16))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.profile_account_code), style = MaterialTheme.typography.bodyLarge)
            Text(code, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Text(stringResource(R.string.profile_account_code_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
