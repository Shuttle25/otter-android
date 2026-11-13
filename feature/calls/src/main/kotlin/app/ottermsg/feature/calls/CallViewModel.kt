package app.ottermsg.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.calls.CallManager
import app.ottermsg.core.data.calls.CallNotice
import app.ottermsg.core.data.calls.CallOutcome
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** What the call screen says under the name. */
sealed interface CallStatus {
    data object None : CallStatus
    /** Outgoing, offer not yet delivered: «Соединение…». */
    data object Dialing : CallStatus
    data object Ringing : CallStatus
    data object Incoming : CallStatus
    /** Answered, media not yet connected: «Соединяем…». */
    data object Connecting : CallStatus
    data object Reconnecting : CallStatus
    data class Timer(val seconds: Long) : CallStatus
    data class Ended(val outcome: CallOutcome, val durationS: Long) : CallStatus
    data class Notice(val notice: CallNotice) : CallStatus
}

/** Pure mapping of the call state (unit-tested). Idle → [CallStatus.None] (the end text comes from `ended`). */
fun callStatus(state: CallState, nowMs: Long): CallStatus = when (state) {
    CallState.Idle -> CallStatus.None
    is CallState.Outgoing -> if (state.ringing) CallStatus.Ringing else CallStatus.Dialing
    is CallState.Incoming -> CallStatus.Incoming
    is CallState.Connecting -> CallStatus.Connecting
    is CallState.Active -> if (state.lostSinceMs != null) CallStatus.Reconnecting
        else CallStatus.Timer(((nowMs - state.startMs) / 1000).coerceAtLeast(0))
}

fun timerText(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)

data class CallUi(
    val name: String = "", val seed: ByteArray = ByteArray(0), val status: CallStatus = CallStatus.None,
    val incoming: Boolean = false, val inCall: Boolean = false, val muted: Boolean = false, val speaker: Boolean = false,
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val manager: CallManager, private val audio: CallAudio, private val accounts: AccountRepository,
) : ViewModel() {
    private var lastPeer: Peer? = null
    private var lastActiveStart: Long? = null
    private val endStatus = MutableStateFlow<CallStatus?>(null)
    /** A call is being started from this screen (call back) or was just dialed from a chat: do not close on Idle. */
    private val pending = MutableStateFlow(false)
    private val _warnPeer = MutableStateFlow<Peer?>(null)
    /** «Собеседник может не услышать звонок» before calling back. */
    val warnPeer: StateFlow<Peer?> = _warnPeer
    private val _dialed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** This screen dialed: the activity starts the call service. */
    val dialed: SharedFlow<Unit> = _dialed
    private val _close = MutableStateFlow(false)
    val close: StateFlow<Boolean> = _close

    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }

    val ui: StateFlow<CallUi> = combine(manager.state, ticker, endStatus, manager.muted, audio.speaker) { s, now, end, muted, speaker ->
        peerOf(s)?.let { lastPeer = it }
        if (s is CallState.Active) lastActiveStart = s.startMs
        val p = lastPeer
        CallUi(p?.name.orEmpty(), p?.id ?: ByteArray(0), if (s == CallState.Idle) end ?: CallStatus.None else callStatus(s, now),
            incoming = s is CallState.Incoming, inCall = s != CallState.Idle && s !is CallState.Incoming, muted = muted, speaker = speaker)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CallUi())

    /** Proximity wake lock: answered call with the audio at the ear. */
    val atEar: StateFlow<Boolean> = combine(manager.state, audio.earpiece) { s, ear -> ear && (s is CallState.Connecting || s is CallState.Active) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            manager.ended.collect { o ->
                val start = lastActiveStart
                val d = if (start != null && (o == CallOutcome.OUTGOING || o == CallOutcome.INCOMING)) (System.currentTimeMillis() - start) / 1000 else 0
                endStatus.value = CallStatus.Ended(o, d)
            }
        }
        viewModelScope.launch { manager.notices.collect { endStatus.value = CallStatus.Notice(it); pending.value = false } }
    }

    private var attached = false

    /**
     * Starts the close rule once the activity has handled its intent (so a dial just made is not mistaken for "no
     * call"): closes 2 s after the call ends, at once when there was nothing to show.
     */
    fun attach() {
        if (attached) return
        attached = true
        viewModelScope.launch {
            combine(manager.state, pending, _warnPeer) { s, p, w -> s == CallState.Idle && !p && w == null }.collectLatest { idle ->
                if (!idle) { if (manager.state.value != CallState.Idle) endStatus.value = null; return@collectLatest }
                if (lastPeer != null || endStatus.value != null) delay(CLOSE_DELAY_MS)
                _close.value = true
            }
        }
    }

    /** A call back waits for the microphone permission: keep the screen open. */
    fun holdOpen() { pending.value = true }

    /** Opened right after a dial from a chat: the call state may still be Idle for a moment. */
    fun expectCall() { if (manager.state.value == CallState.Idle) awaitStart() }

    /** «Перезвонить» from a missed-call notification: same flow as the chat's call button (after the mic permission). */
    fun callBack(peer: Peer) {
        lastPeer = peer
        pending.value = true
        viewModelScope.launch {
            accounts.load() // every background entry point loads the account first (D61)
            if (manager.state.value != CallState.Idle) { pending.value = false; return@launch }
            if (manager.peerHasPush(peer.id)) dial(peer) else _warnPeer.value = peer
        }
    }

    fun confirmCallBack() { _warnPeer.value?.let { _warnPeer.value = null; dial(it) } }
    fun cancelCallBack() { _warnPeer.value = null; pending.value = false; lastPeer = null }
    fun cancelPending() { pending.value = false }

    private fun dial(peer: Peer) {
        manager.dial(peer)
        _dialed.tryEmit(Unit)
        awaitStart()
    }

    private fun awaitStart() {
        pending.value = true
        viewModelScope.launch {
            withTimeoutOrNull(START_WAIT_MS) { manager.state.first { it != CallState.Idle } }
            pending.value = false
        }
    }

    fun accept() = manager.accept()
    fun decline() = manager.decline()
    fun hangUp() = manager.hangUp()
    fun toggleMute() = manager.setMuted(!manager.muted.value)
    fun toggleSpeaker() { viewModelScope.launch { runCatching { audio.toggleSpeaker() } } }

    private fun peerOf(s: CallState): Peer? = when (s) {
        is CallState.Outgoing -> s.peer; is CallState.Incoming -> s.peer; is CallState.Connecting -> s.peer
        is CallState.Active -> s.peer; CallState.Idle -> null
    }

    companion object {
        const val CLOSE_DELAY_MS = 2_000L
        /** CallManager waits up to 10 s for the node connection before it gives up with a notice. */
        const val START_WAIT_MS = 20_000L
    }
}
