package app.ottermsg.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.messaging.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** «Ответить» / «Прочитано» on a message notification (D62), handled without opening the app. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var messages: MessageRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val cid = intent.getLongExtra(MessageNotifier.EXTRA_CONVERSATION, -1)
        if (cid < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                if (intent.action == ACTION_REPLY) {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString().orEmpty()
                    if (text.isNotBlank()) messages.sendText(cid, text)
                }
                messages.markConversationRead(cid) // also cancels the notification (MessageAlerts.onRead)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.ottermsg.REPLY"
        const val ACTION_READ = "app.ottermsg.MARK_READ"
        const val KEY_TEXT = "text"
    }
}
