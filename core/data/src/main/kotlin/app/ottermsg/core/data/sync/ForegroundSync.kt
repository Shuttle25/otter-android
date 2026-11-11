package app.ottermsg.core.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.media.MediaTransfers
import app.ottermsg.core.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs while the app is visible (no background delivery before M5): start on ON_START, stop 10 s after ON_STOP.
 * Returning to the app while the connection waits out a reconnect backoff retries at once (no ≤ 72 s wait).
 */
@Singleton
class ForegroundSync internal constructor(
    private val start: () -> Unit, private val stop: () -> Unit, private val scope: CoroutineScope,
    private val retryIfWaiting: () -> Unit = {},
) : DefaultLifecycleObserver {
    @Inject constructor(engine: SyncEngine, network: NetworkMonitor, media: MediaTransfers, @ApplicationScope scope: CoroutineScope) : this(
        { engine.start(); network.start(engine::onNetworkChanged); media.kick() /* Wi-Fi may have changed: auto-download */ },
        { engine.stop(); network.stop() },
        scope,
        { if (engine.connection.value is ConnectionState.Waiting) engine.retryNow() },
    )

    private var pendingStop: Job? = null

    override fun onStart(owner: LifecycleOwner) = foreground()
    override fun onStop(owner: LifecycleOwner) = background()

    fun foreground() {
        pendingStop?.cancel(); pendingStop = null
        start()
        retryIfWaiting()
    }

    fun background() {
        pendingStop?.cancel()
        pendingStop = scope.launch { delay(STOP_DELAY_MS); stop() }
    }

    companion object { const val STOP_DELAY_MS = 10_000L }
}
