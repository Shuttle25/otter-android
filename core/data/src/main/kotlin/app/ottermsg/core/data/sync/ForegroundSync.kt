package app.ottermsg.core.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.media.MediaTransfers
import app.ottermsg.core.data.push.AppVisibility
import app.ottermsg.core.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the engine's foreground lease while the app is visible (wake jobs hold their own): acquire on ON_START,
 * release 10 s after ON_STOP.
 * Returning to the app while the connection waits out a reconnect backoff retries at once (no ≤ 72 s wait).
 */
@Singleton
class ForegroundSync internal constructor(
    private val start: () -> Unit, private val stop: () -> Unit, private val scope: CoroutineScope,
    private val retryIfWaiting: () -> Unit = {},
    private val visibility: AppVisibility = AppVisibility(),
) : DefaultLifecycleObserver {
    @Inject constructor(engine: SyncEngine, network: NetworkMonitor, media: MediaTransfers, @ApplicationScope scope: CoroutineScope,
                        visibility: AppVisibility) : this(
        { engine.acquire(SyncEngine.FOREGROUND); network.start(engine::onNetworkChanged); media.kick() /* Wi-Fi may have changed: auto-download */ },
        { engine.release(SyncEngine.FOREGROUND); network.stop() },
        scope,
        { if (engine.connection.value is ConnectionState.Waiting) engine.retryNow() },
        visibility,
    )

    private var pendingStop: Job? = null

    override fun onStart(owner: LifecycleOwner) = foreground()
    override fun onStop(owner: LifecycleOwner) = background()

    fun foreground() {
        visibility.visible = true
        pendingStop?.cancel(); pendingStop = null
        start()
        retryIfWaiting()
    }

    fun background() {
        visibility.visible = false
        pendingStop?.cancel()
        pendingStop = scope.launch { delay(STOP_DELAY_MS); stop() }
    }

    companion object { const val STOP_DELAY_MS = 10_000L }
}
