package app.ottermsg.core.data.push

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

enum class PushMode { FAST, SAVER }

/** D61: FCM ("Быстрые уведомления") while it demonstrably works, else the 15-min battery saver. */
@Singleton
class PushMonitor @Inject constructor(private val settings: Settings, private val clock: Clock) {
    @Volatile private var missSession = -1L

    suspend fun mode(): PushMode = modeOf(settings.pushAvailable(), settings.pushToken(), recent(settings.pushMisses()))

    fun observeMode(): Flow<PushMode> = combine(settings.observePushAvailable(), settings.observePushToken(),
        settings.observePushMisses()) { a, t, m -> modeOf(a, t, recent(m)) }

    /** An FCM wake-up arrived: FCM works. */
    suspend fun onWake() {
        settings.setPushLastWakeMs(clock.now())
        settings.setPushMisses(emptyList())
    }

    /** The engine went fully offline (no lease left). */
    suspend fun onOffline() = settings.setLastOfflineMs(clock.now())

    /**
     * Envelopes created at [createdMs] were delivered in session [sessionId] that logged in at [sessionStartMs].
     * A miss: created after the engine last went offline and after the last FCM wake, and at least [LATE_MS] before
     * the session started. At most one miss per session.
     */
    suspend fun onDelivered(createdMs: List<Long>, sessionStartMs: Long, sessionId: Long) {
        if (sessionId == missSession) return
        val offlineSince = settings.lastOfflineMs()
        if (offlineSince == 0L) return
        val lastWake = settings.pushLastWakeMs()
        val missed = createdMs.any { it > offlineSince && it > lastWake && sessionStartMs - it >= LATE_MS }
        if (!missed) return
        missSession = sessionId
        val now = clock.now()
        settings.setPushMisses((settings.pushMisses() + now).filter { now - it < DAY_MS })
    }

    private fun recent(m: List<Long>): Int { val now = clock.now(); return m.count { now - it < DAY_MS } }

    companion object {
        const val DAY_MS = 86_400_000L
        const val LATE_MS = 60_000L
        const val MAX_MISSES = 3
        fun modeOf(available: Boolean, token: String?, misses: Int): PushMode =
            if (available && !token.isNullOrEmpty() && misses < MAX_MISSES) PushMode.FAST else PushMode.SAVER
    }
}
