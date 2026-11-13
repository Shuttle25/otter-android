package app.ottermsg.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.ottermsg.core.data.calls.CallManager
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import app.ottermsg.core.ui.theme.OtterTheme
import app.ottermsg.feature.calls.CallRoute
import app.ottermsg.feature.calls.CallViewModel
import app.ottermsg.feature.calls.R as CallsR
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The call screen (D80): shown over the lock screen, turns the screen on for an incoming call, holds the proximity
 * wake lock while the audio is at the ear, closes 2 s after the call ends.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {
    @Inject lateinit var manager: CallManager
    private val vm: CallViewModel by viewModels()
    private var proximity: PowerManager.WakeLock? = null
    /** What to do once the microphone permission is answered. */
    private var afterMic: ((Boolean) -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterMic
        afterMic = null
        next?.invoke(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) handle(intent)
        vm.attach()
        setContent { OtterTheme { CallRoute(vm, onAccept = ::accept) } }
        lifecycleScope.launch { vm.dialed.collect { startCallService() } }
        lifecycleScope.launch { vm.close.collect { if (it) finishAndRemoveTask() } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.atEar.collect(::setProximity) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        setProximity(false)
        super.onStop()
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER -> accept()
            ACTION_OUTGOING -> { vm.expectCall(); startCallService() }
            ACTION_CALL_BACK -> {
                val id = intent.getStringExtra(EXTRA_PEER_ID)?.let(::unhex) ?: return
                val peer = Peer.of(id, intent.getStringExtra(EXTRA_PEER_NAME).orEmpty())
                vm.holdOpen()
                intent.getIntExtra(EXTRA_NOTIFICATION, 0).takeIf { it != 0 }?.let { NotificationManagerCompat.from(this).cancel(it) }
                withMic { granted -> if (granted) vm.callBack(peer) else { micDenied(); vm.cancelCallBack() } }
            }
        }
    }

    /** Accept (screen button or the notification's Accept): microphone permission first, then the service with audio. */
    private fun accept() {
        if (manager.state.value !is CallState.Incoming) return
        withMic { granted ->
            if (!granted) { micDenied(); manager.decline(); return@withMic }
            manager.accept()
            startCallService()
        }
    }

    private fun withMic(then: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then(true)
        else { afterMic = then; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun micDenied() = Toast.makeText(this, CallsR.string.call_mic_needed, Toast.LENGTH_LONG).show()

    /** From the visible activity a foreground service may start (and use the microphone). */
    private fun startCallService() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, CallService::class.java)) }
    }

    @SuppressLint("Wakelock") // released when the call ends or the screen stops (onStop); the timeout is a backstop
    private fun setProximity(on: Boolean) {
        val pm = getSystemService(PowerManager::class.java)
        if (on) {
            if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "otter:call").apply { acquire(PROXIMITY_MAX_MS) }
            }
        } else {
            proximity?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
            proximity = null
        }
    }

    companion object {
        const val ACTION_SHOW = "app.ottermsg.calls.SHOW"
        const val ACTION_ANSWER = "app.ottermsg.calls.ANSWER"
        const val ACTION_OUTGOING = "app.ottermsg.calls.OUTGOING"
        const val ACTION_CALL_BACK = "app.ottermsg.calls.CALL_BACK"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_NOTIFICATION = "notification_id"
        private const val PROXIMITY_MAX_MS = 4 * 60 * 60 * 1000L

        private fun intent(c: Context, action: String) = Intent(c, CallActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun show(c: Context) = intent(c, ACTION_SHOW)
        fun answer(c: Context) = intent(c, ACTION_ANSWER)
        /** Right after a dial from a chat. */
        fun outgoing(c: Context) = intent(c, ACTION_OUTGOING)
        fun callBack(c: Context, peer: Peer) = intent(c, ACTION_CALL_BACK)
            .putExtra(EXTRA_PEER_ID, peer.id.joinToString("") { "%02x".format(it) }).putExtra(EXTRA_PEER_NAME, peer.name)

        private fun unhex(s: String): ByteArray? = runCatching {
            require(s.length % 2 == 0 && s.isNotEmpty())
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
