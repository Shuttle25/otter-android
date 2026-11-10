package app.ottermsg.core.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.ottermsg.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Syncs while the app is visible (M1 has no background delivery): start on ON_START, stop 10 s after ON_STOP. */
@Singleton
class ForegroundSync internal constructor(
    private val start: () -> Unit, private val stop: () -> Unit, private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    @Inject constructor(engine: SyncEngine, @ApplicationScope scope: CoroutineScope) : this(engine::start, engine::stop, scope)

    private var pendingStop: Job? = null

    override fun onStart(owner: LifecycleOwner) = foreground()
    override fun onStop(owner: LifecycleOwner) = background()

    fun foreground() {
        pendingStop?.cancel(); pendingStop = null
        start()
    }

    fun background() {
        pendingStop?.cancel()
        pendingStop = scope.launch { delay(STOP_DELAY_MS); stop() }
    }

    companion object { const val STOP_DELAY_MS = 10_000L }
}
