package app.ottermsg.feature.chats

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

@Composable
fun InviteRoute(onBack: () -> Unit, vm: InviteViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.invite_share)
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.invite_title), onBack = onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Dimens.screenGutter),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s16),
        ) {
            Text(stringResource(R.string.invite_text), style = MaterialTheme.typography.bodyLarge)
            when (val s = state) {
                InviteUiState.Loading -> Box(Modifier.heightIn(min = 256.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                InviteUiState.Error -> {
                    Text(stringResource(R.string.invite_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = vm::create, modifier = Modifier.heightIn(min = Dimens.minTouch)) { Text(stringResource(R.string.invite_retry)) }
                }
                is InviteUiState.Ready -> {
                    Box(Modifier.background(Color.White, MaterialTheme.shapes.large).padding(Dimens.s12)) {
                        QrImage(s.link, stringResource(R.string.invite_qr_description))
                    }
                    Text(stringResource(R.string.invite_validity), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.link)
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Text("  $shareTitle")
                    }
                }
            }
        }
    }
}

