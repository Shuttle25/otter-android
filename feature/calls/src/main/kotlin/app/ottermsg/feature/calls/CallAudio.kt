package app.ottermsg.feature.calls

import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio route of the current call. With Telecom (the normal case) the route changes only through
 * `requestEndpointChange` ([request], set by CallService inside `addCall`); without Telecom (it refused the call)
 * CallService sets [fallback], a plain speakerphone switch.
 */
@Singleton
class CallAudio @Inject constructor() {
    val available = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
    val current = MutableStateFlow<CallEndpointCompat?>(null)
    @Volatile var request: (suspend (CallEndpointCompat) -> Unit)? = null
    @Volatile var fallback: ((Boolean) -> Unit)? = null
    val fallbackSpeaker = MutableStateFlow(false)

    val speaker: Flow<Boolean> = combine(current, fallbackSpeaker) { c, f -> if (c != null) c.type == CallEndpointCompat.TYPE_SPEAKER else f }
    /** Audio at the ear: the proximity sensor may turn the screen off. */
    val earpiece: Flow<Boolean> = combine(current, fallbackSpeaker) { c, f -> if (c != null) c.type == CallEndpointCompat.TYPE_EARPIECE else !f }

    suspend fun toggleSpeaker() {
        val req = request
        if (req != null) {
            toggleTarget(current.value?.type, available.value.map { it.type })?.let { t -> available.value.firstOrNull { it.type == t }?.let { req(it) } }
            return
        }
        val fb = fallback ?: return
        val on = !fallbackSpeaker.value
        fb(on)
        fallbackSpeaker.value = on
    }

    fun reset() {
        request = null; fallback = null
        available.value = emptyList(); current.value = null; fallbackSpeaker.value = false
    }

    companion object {
        /** Speaker ↔ the private route (Bluetooth, then wired headset, then earpiece). Null: nothing to switch to. */
        fun toggleTarget(current: Int?, available: List<Int>): Int? =
            if (current != CallEndpointCompat.TYPE_SPEAKER) CallEndpointCompat.TYPE_SPEAKER.takeIf { it in available }
            else listOf(CallEndpointCompat.TYPE_BLUETOOTH, CallEndpointCompat.TYPE_WIRED_HEADSET, CallEndpointCompat.TYPE_EARPIECE)
                .firstOrNull { it in available }
    }
}
