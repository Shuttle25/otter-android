package app.ottermsg.feature.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.ui.components.OtterStatusBanner
import kotlinx.coroutines.delay

const val CONNECTING_GRACE_MS = 2_000L

/** Guide §8: offline → calm strip; connecting only when it takes noticeably long; online → nothing. No node names. */
fun bannerFor(state: ConnectionState, connectingSinceMs: Long, nowMs: Long): Int? = when (state) {
    is ConnectionState.Online -> null
    is ConnectionState.Waiting, ConnectionState.Offline -> R.string.chats_offline
    ConnectionState.Connecting -> if (nowMs - connectingSinceMs >= CONNECTING_GRACE_MS) R.string.chats_connecting else null
}

@Composable
fun ConnectionBanner(state: ConnectionState) {
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state is ConnectionState.Connecting) {
        since = System.currentTimeMillis(); now = since
        if (state is ConnectionState.Connecting) { delay(CONNECTING_GRACE_MS); now = System.currentTimeMillis() }
    }
    bannerFor(state, since, now)?.let { OtterStatusBanner(stringResource(it)) }
}
