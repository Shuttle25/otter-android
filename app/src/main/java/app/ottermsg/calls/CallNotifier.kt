package app.ottermsg.calls

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.ottermsg.MainActivity
import app.ottermsg.R
import app.ottermsg.core.data.calls.CallAlerts
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import app.ottermsg.notify.Channels
import app.ottermsg.notify.MessageNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ringing and missed calls (D80). An incoming call posts a full-screen CallStyle notification on `calls` and then tries
 * to start [CallService]; if Android refuses a background foreground-service start, the notification alone rings and
 * the service starts on Accept (from [CallActivity]). Like any phone call, the caller's name shows on the lock screen.
 */
@Singleton
class CallNotifier @Inject constructor(@ApplicationContext private val ctx: Context) : CallAlerts {
    private val nm = NotificationManagerCompat.from(ctx)

    override fun incoming(call: CallState.Incoming) {
        post(INCOMING_ID, incomingNotification(call))
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java))
        } catch (e: Exception) {
            log.log(Level.INFO, "call service not started while ringing; it starts on Accept", e)
        }
    }

    override fun missed(peer: Peer, conversationId: Long) {
        val main = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationId > 0) main.setAction(MessageNotifier.ACTION_OPEN).putExtra(MessageNotifier.EXTRA_CONVERSATION, conversationId)
        val key = if (conversationId > 0) conversationId.toInt() else peer.name.hashCode()
        val open = PendingIntent.getActivity(ctx, MISSED_BASE + key, main, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val back = PendingIntent.getActivity(ctx, CALL_BACK_BASE + key, CallActivity.callBack(ctx, peer).putExtra(CallActivity.EXTRA_NOTIFICATION, MISSED_BASE + key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(ctx, Channels.MISSED_CALLS).setSmallIcon(R.drawable.ic_stat_otter)
            .setContentTitle(ctx.getString(R.string.call_missed_title)).build()
        val n = NotificationCompat.Builder(ctx, Channels.MISSED_CALLS).setSmallIcon(R.drawable.ic_stat_otter)
            .setContentTitle(ctx.getString(R.string.call_missed_title)).setContentText(peer.name)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL).setAutoCancel(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .addAction(0, ctx.getString(R.string.call_back), back).build()
        post(MISSED_BASE + key, n)
    }

    override fun released() {
        nm.cancel(INCOMING_ID)
    }

    fun incomingNotification(call: CallState.Incoming): Notification {
        val person = Person.Builder().setName(call.peer.name).setImportant(true).build()
        val full = PendingIntent.getActivity(ctx, 1, CallActivity.show(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val answer = PendingIntent.getActivity(ctx, 2, CallActivity.answer(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(ctx, 3, CallActionReceiver.decline(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, Channels.CALLS).setSmallIcon(R.drawable.ic_stat_otter)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setContentIntent(full).setFullScreenIntent(full, true).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // name + Accept/Decline on the lock screen, like any phone call
            .build().apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    fun ongoingNotification(name: String): Notification {
        val person = Person.Builder().setName(name).setImportant(true).build()
        val hang = PendingIntent.getBroadcast(ctx, 4, CallActionReceiver.hangUp(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(ctx, 5, CallActivity.show(ctx), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(ctx, Channels.CALLS_ONGOING).setSmallIcon(R.drawable.ic_stat_otter)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hang)).setContentIntent(open)
            .setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
    }

    /** Placeholder for the moment between a dial and the first call state (the service must be foreground at once). */
    fun startingNotification(): Notification =
        NotificationCompat.Builder(ctx, Channels.CALLS_ONGOING).setSmallIcon(R.drawable.ic_stat_otter)
            .setContentTitle(ctx.getString(R.string.call_starting)).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL).build()

    private fun post(id: Int, n: Notification) {
        try { nm.notify(id, n) } catch (_: SecurityException) { /* notifications not allowed */ }
    }

    companion object {
        const val INCOMING_ID = Int.MAX_VALUE - 3
        const val ONGOING_ID = Int.MAX_VALUE - 4
        const val MISSED_BASE = 1_000_000
        private const val CALL_BACK_BASE = 2_000_000
        private val log = Logger.getLogger("CallNotifier")
    }
}
