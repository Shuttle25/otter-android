package app.ottermsg.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.data.security.AppLock
import app.ottermsg.push.WakeScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** «Ответить» / «Прочитано» on a message notification (D62), handled without opening the app. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var messages: MessageRepository
    @Inject lateinit var wake: WakeScheduler
    @Inject lateinit var appLock: AppLock
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val cid = intent.getLongExtra(MessageNotifier.EXTRA_CONVERSATION, -1)
        if (cid < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                accounts.load() ?: return@launch // the process may have started just for this action
                appLock.init()
                // D121: no «Ответить» while the app lock is on, also from a notification posted before it was turned on.
                if (intent.action == ACTION_REPLY && appLock.enabled.value) return@launch
                if (intent.action == ACTION_REPLY) {
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString().orEmpty()
                    if (text.isNotBlank()) messages.sendText(cid, text)
                }
                messages.markConversationRead(cid) // also cancels the notification (MessageAlerts.onRead)
                wake.wakeNow() // deliver the reply / read receipts without opening the app
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.ottermsg.REPLY"
        const val ACTION_READ = "app.ottermsg.MARK_READ"
        const val KEY_TEXT = "text"
    }
}
