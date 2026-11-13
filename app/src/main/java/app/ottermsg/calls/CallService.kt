package app.ottermsg.calls

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.telecom.DisconnectCause
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallsManager
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.calls.CallManager
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import app.ottermsg.feature.calls.CallAudio
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/**
 * The call while it lasts (D80): a `phoneCall` foreground service (`|microphone` once the call is answered and the
 * microphone is granted) with a CallStyle notification, and the call registered with Telecom through core-telecom
 * (audio focus, Bluetooth, routing, other calls). Stops itself when the call manager is Idle again.
 */
@AndroidEntryPoint
class CallService : Service() {
    @Inject lateinit var manager: CallManager
    @Inject lateinit var notifier: CallNotifier
    @Inject lateinit var audio: CallAudio
    @Inject lateinit var accounts: AccountRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var telecomJob: Job? = null
    private var telecomFailed = false
    private var seenCall = false
    private var foregroundKey: Pair<Int, Int>? = null // (notification id, service type) last used
    private var shownState: CallState? = null
    private var modeSet = false
    private var idleJob: Job? = null
    private var lastStartId = 0
    private val am by lazy { getSystemService(AudioManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            accounts.load() // every background entry point loads the account first (D61)
            manager.state.collect { onState(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start via startForegroundService must reach startForeground in time, whatever the call state.
        lastStartId = startId
        val s = manager.state.value
        ensureForeground(s, force = true)
        // Dialed from a chat, the first call state may still be on its way; give up if no call shows up.
        if (s == CallState.Idle) {
            idleJob?.cancel()
            idleJob = scope.launch { delay(START_WAIT_MS); if (manager.state.value == CallState.Idle) finish() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        restoreAudioMode()
        audio.reset()
        scope.cancel()
        super.onDestroy()
    }

    private fun onState(s: CallState) {
        if (s == CallState.Idle) {
            if (seenCall) finish()
            return
        }
        idleJob?.cancel(); idleJob = null
        seenCall = true
        ensureForeground(s)
        if (telecomJob == null && !telecomFailed) telecomJob = scope.launch { telecom(s) }
        if (telecomFailed && (s is CallState.Connecting || s is CallState.Active)) fallbackAudio()
    }

    private fun ensureForeground(s: CallState, force: Boolean = false) {
        if (!force && s == shownState) return
        val (id, n) = when (s) {
            is CallState.Incoming -> CallNotifier.INCOMING_ID to notifier.incomingNotification(s)
            is CallState.Outgoing -> CallNotifier.ONGOING_ID to notifier.ongoingNotification(s.peer.name)
            is CallState.Connecting -> CallNotifier.ONGOING_ID to notifier.ongoingNotification(s.peer.name)
            is CallState.Active -> CallNotifier.ONGOING_ID to notifier.ongoingNotification(s.peer.name)
            CallState.Idle -> {
                if (foregroundKey != null) return
                CallNotifier.ONGOING_ID to notifier.startingNotification()
            }
        }
        val answered = s is CallState.Outgoing || s is CallState.Connecting || s is CallState.Active
        val mic = answered && Build.VERSION.SDK_INT >= 30 && hasMic()
        val phone = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0
        val type = if (mic) phone or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else phone
        try {
            ServiceCompat.startForeground(this, id, n, type)
        } catch (e: Exception) {
            // microphone type refused (app not visible when the call was answered from elsewhere): phoneCall only
            log.log(Level.WARNING, "startForeground type $type refused", e)
            try { ServiceCompat.startForeground(this, id, n, phone) } catch (e2: Exception) { log.log(Level.WARNING, "startForeground refused", e2) }
        }
        foregroundKey = id to type
        shownState = s
        if (id != CallNotifier.INCOMING_ID) NotificationManagerCompat.from(this).cancel(CallNotifier.INCOMING_ID) // stops the ringing
    }

    private fun finish() {
        seenCall = false
        shownState = null
        foregroundKey = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(CallNotifier.INCOMING_ID)
        restoreAudioMode()
        val job = telecomJob
        telecomJob = null
        telecomFailed = false
        scope.launch {
            // the Telecom call disconnects itself on Idle; do not leave it behind if it hangs
            withTimeoutOrNull(TELECOM_END_MS) { job?.join() }
            job?.cancel()
            if (seenCall) return@launch // the next call already started in this service
            audio.reset()
            stopSelfResult(lastStartId)
        }
    }

    private suspend fun telecom(first: CallState) {
        val peer = peerOf(first) ?: return
        val incoming = when (first) {
            is CallState.Incoming -> true
            is CallState.Connecting -> !first.outgoing
            is CallState.Active -> !first.outgoing
            else -> false
        }
        try {
            val cm = CallsManager(this)
            cm.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            val attrs = CallAttributesCompat(peer.name, ("otter:" + peer.id.joinToString("") { "%02x".format(it) }).toUri(),
                if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
                CallAttributesCompat.CALL_TYPE_AUDIO_CALL, CallAttributesCompat.SUPPORTS_SET_INACTIVE)
            // The remote-surface lambdas only post to the call manager (they must return within 5 s).
            cm.addCall(attrs, onAnswer = { manager.accept() }, onDisconnect = { manager.hangUp() },
                onSetActive = {}, onSetInactive = { manager.hangUp() /* no hold in V1 */ }) {
                val call = this
                audio.request = { call.requestEndpointChange(it) }
                launch { availableEndpoints.collect { audio.available.value = it } }
                launch { currentCallEndpoint.collect { audio.current.value = it } }
                launch {
                    var answered = false
                    var active = false
                    manager.state.collect { st ->
                        when (st) {
                            is CallState.Connecting, is CallState.Active -> {
                                if (incoming && !answered) { answered = true; call.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) }
                                if (!incoming && st is CallState.Active && !active) { active = true; call.setActive() }
                            }
                            CallState.Idle -> { call.disconnect(DisconnectCause(DisconnectCause.LOCAL)); call.cancel() }
                            else -> {}
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Telecom refused (no telecom on the device, another call…): the call goes on without system integration
            log.log(Level.WARNING, "telecom addCall failed", e)
            telecomFailed = true
            val s = manager.state.value
            if (s is CallState.Connecting || s is CallState.Active) fallbackAudio()
        } finally {
            audio.request = null
        }
    }

    /** Without Telecom: voice-call audio mode (earpiece, echo cancellation) and a plain speakerphone switch. */
    @Suppress("DEPRECATION")
    private fun fallbackAudio() {
        if (modeSet) return
        modeSet = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = false
        audio.fallback = { on -> am.isSpeakerphoneOn = on }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioMode() {
        if (!modeSet) return
        modeSet = false
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun peerOf(s: CallState): Peer? = when (s) {
        is CallState.Outgoing -> s.peer; is CallState.Incoming -> s.peer; is CallState.Connecting -> s.peer
        is CallState.Active -> s.peer; CallState.Idle -> null
    }

    companion object {
        private const val START_WAIT_MS = 20_000L
        private const val TELECOM_END_MS = 3_000L
        private val log = Logger.getLogger("CallService")
    }
}
