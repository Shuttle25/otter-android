package app.ottermsg.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.ottermsg.R

/** Notification channels (D62): direct messages, groups, and the silent background-check channel. */
object Channels {
    const val MESSAGES = "messages"
    const val GROUPS = "groups"
    const val SYNC = "sync"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(MESSAGES, context.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(GROUPS, context.getString(R.string.notif_channel_groups), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(SYNC, context.getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
        ))
    }
}
