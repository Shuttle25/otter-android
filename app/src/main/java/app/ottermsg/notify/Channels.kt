package app.ottermsg.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.ottermsg.R

/**
 * Notification channels (D62): direct messages, groups, Otter News (D64), the silent background-check channel, and
 * calls (D80): ringing (ringtone, vibration), the ongoing call (silent) and missed calls. A channel's sound is fixed at
 * creation; [create] runs at every app start, so existing installs get the new channels after the update.
 */
object Channels {
    const val MESSAGES = "messages"
    const val GROUPS = "groups"
    const val SYNC = "sync"
    const val NEWS = "news"
    const val CALLS = "calls"
    const val CALLS_ONGOING = "calls_ongoing"
    const val MISSED_CALLS = "missed_calls"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(MESSAGES, context.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(GROUPS, context.getString(R.string.notif_channel_groups), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(NEWS, context.getString(R.string.notif_channel_news), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SYNC, context.getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
            NotificationChannel(CALLS, context.getString(R.string.notif_channel_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                setShowBadge(false)
            },
            NotificationChannel(CALLS_ONGOING, context.getString(R.string.notif_channel_calls_ongoing), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
            NotificationChannel(MISSED_CALLS, context.getString(R.string.notif_channel_missed_calls), NotificationManager.IMPORTANCE_HIGH),
        ))
    }
}
