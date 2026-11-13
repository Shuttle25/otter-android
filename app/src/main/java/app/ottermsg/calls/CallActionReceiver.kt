package app.ottermsg.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ottermsg.core.data.calls.CallManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** «Отклонить» / «Завершить» on the call notification: only posts the event to the call manager. */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {
    @Inject lateinit var manager: CallManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> manager.decline()
            ACTION_HANGUP -> manager.hangUp()
        }
    }

    companion object {
        const val ACTION_DECLINE = "app.ottermsg.calls.DECLINE"
        const val ACTION_HANGUP = "app.ottermsg.calls.HANGUP"
        fun decline(c: Context): Intent = Intent(c, CallActionReceiver::class.java).setAction(ACTION_DECLINE)
        fun hangUp(c: Context): Intent = Intent(c, CallActionReceiver::class.java).setAction(ACTION_HANGUP)
    }
}
