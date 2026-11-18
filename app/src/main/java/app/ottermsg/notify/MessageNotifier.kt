package app.ottermsg.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import app.ottermsg.MainActivity
import app.ottermsg.R
import app.ottermsg.core.data.push.ActiveConversation
import app.ottermsg.core.data.push.AppVisibility
import app.ottermsg.core.data.push.MessageAlerts
import app.ottermsg.core.data.security.AppLock
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.data.news.NewsRepository
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.ConversationType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One MessagingStyle notification per conversation (id = conversation id) with its unread messages, grouped under one
 * summary; private on the lock screen; «Ответить» and «Прочитано» actions; tap opens the chat (D62).
 * While the app lock is on (D121): only «Otter / Новое сообщение», no names, no text, no actions.
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context, private val db: OtterDatabase, private val settings: Settings,
    private val active: ActiveConversation, private val visibility: AppVisibility, private val appLock: AppLock,
) : MessageAlerts {
    private val nm = NotificationManagerCompat.from(context)
    private val groupKey get() = context.packageName + GROUP

    override suspend fun onIncoming(conversationId: Long) {
        if (!settings.notificationsEnabled() || !permitted()) return
        appLock.init() // a process started by a push has not read the lock state yet (idempotent)
        if (visibility.visible && active.id == conversationId) return
        val conv = db.conversationDao().get(conversationId) ?: return
        if (conv.type == ConversationType.NEWS) return postNews(conv)
        val unread = db.messageDao().unreadReceiptsPending(conversationId)
        if (unread.isEmpty()) return
        val names = unread.map { it.senderId }.distinctBy { it.toHexKey() }
            .associate { id -> id.toHexKey() to (db.contactDao().get(id)?.displayName ?: conv.title) }
        val group = conv.type == ConversationType.GROUP
        val content = NotificationContent.build(conv.title, group, unread, names, context.getString(R.string.notif_photo))
        if (content.lines.isEmpty()) return
        post(conversationId, content)
    }

    /**
     * News (D64, D67): the unread news messages (no receipts; the newest `unread` ones) created within the last 3 days,
     * from «Выдра» in «Новости Выдры», on the News channel.
     */
    private suspend fun postNews(conv: ConversationEntity) {
        if (conv.unread <= 0) return
        val since = System.currentTimeMillis() - NewsRepository.NOTIFY_WINDOW_MS
        val unread = db.messageDao().allNews().filter { it.conversationId == conv.id }.takeLast(conv.unread).filter { it.sentMs > since }
        val content = NotificationContent.build(context.getString(R.string.news_title), false, unread, emptyMap(),
            context.getString(R.string.notif_photo), newsSender = context.getString(R.string.news_sender))
        if (content.lines.isEmpty()) return
        post(conv.id, content)
    }

    override fun onRead(conversationId: Long) {
        nm.cancel(conversationId.toInt())
        val others = nm.activeNotifications.any { it.notification.group == groupKey && it.id != SUMMARY_ID && it.id != conversationId.toInt() }
        if (!others) nm.cancel(SUMMARY_ID)
    }

    /** The app lock was just turned on (D121): notifications already shown may carry text and «Ответить». */
    fun hideAll() {
        nm.activeNotifications.filter { it.notification.group == groupKey }.forEach { nm.cancel(it.id) }
        nm.cancel(SUMMARY_ID)
    }

    private fun permitted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(cid: Long, c: NotificationContent) {
        if (appLock.enabled.value && !c.news) return postLocked(cid, c) // news are public within the network
        val me = Person.Builder().setName(context.getString(R.string.notif_me)).build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(if (c.group || c.news) c.title else null)
            .setGroupConversation(c.group)
        c.lines.forEach { style.addMessage(it.text, it.timeMs, Person.Builder().setName(it.sender).build()) }
        val channel = when { c.news -> Channels.NEWS; c.group -> Channels.GROUPS; else -> Channels.MESSAGES }
        val open = openIntent(cid)
        val reply = NotificationCompat.Action.Builder(0, context.getString(R.string.notif_reply),
            actionIntent(cid, NotificationActionReceiver.ACTION_REPLY, mutable = true))
            .addRemoteInput(RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT).setLabel(context.getString(R.string.notif_reply_hint)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()
        val read = NotificationCompat.Action.Builder(0, context.getString(R.string.notif_mark_read),
            actionIntent(cid, NotificationActionReceiver.ACTION_READ, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()
        val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_otter)
            .setContentTitle(context.getString(R.string.notif_public_title)).setContentText(context.getString(R.string.notif_public_text)).build()
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_otter).setStyle(style).setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (c.news) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setGroup(groupKey).setOnlyAlertOnce(false)
            .apply { if (!c.news) addAction(reply) }.addAction(read).build()
        val summary = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_otter)
            .setContentTitle(context.getString(R.string.notif_summary)).setGroup(groupKey).setGroupSummary(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setAutoCancel(true).build()
        try {
            nm.notify(cid.toInt(), n)
            nm.notify(SUMMARY_ID, summary)
        } catch (e: SecurityException) { /* permission revoked meanwhile */ }
    }

    /** D121: the app lock is on: «Otter / Новое сообщение» and nothing else; the tap opens the app behind its lock. */
    private fun postLocked(cid: Long, c: NotificationContent) {
        val n = NotificationCompat.Builder(context, if (c.group) Channels.GROUPS else Channels.MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_otter).setContentTitle(context.getString(R.string.notif_public_title))
            .setContentText(context.getString(R.string.notif_public_text)).setContentIntent(openIntent(cid))
            .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setGroup(groupKey).build()
        try { nm.notify(cid.toInt(), n) } catch (e: SecurityException) { /* permission revoked meanwhile */ }
    }

    private fun openIntent(cid: Long): PendingIntent = PendingIntent.getActivity(context, cid.toInt(),
        Intent(context, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_CONVERSATION, cid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun actionIntent(cid: Long, action: String, mutable: Boolean): PendingIntent =
        PendingIntent.getBroadcast(context, (cid * 4 + if (mutable) 1 else 2).toInt(),
            Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_CONVERSATION, cid),
            // RemoteInput needs a mutable intent (the system adds the typed text); the explicit component keeps it safe.
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ACTION_OPEN = "app.ottermsg.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION = "conversation_id"
        const val SUMMARY_ID = Int.MAX_VALUE - 1
        private const val GROUP = ".messages"
    }
}
