package app.ottermsg.core.data.news

import app.ottermsg.core.crypto.AnnouncementVerifier
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.News
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.push.MessageAlerts
import app.ottermsg.core.data.sync.NewsKeys
import app.ottermsg.core.data.sync.SigContexts
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.NodeApi
import app.ottermsg.proto.v1.Record
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verified announcements as ordinary incoming messages in the one news conversation (D64–D67).
 * A message's id is the announcement's record key (author ‖ id), its sender the news key's ID16.
 */
@Singleton
class NewsRepository @Inject constructor(
    private val db: OtterDatabase, private val keys: NewsKeys, private val clock: Clock, private val alerts: MessageAlerts,
    private val sigs: SigContexts,
) {
    private val mutex = Mutex()

    /** news.list → local messages (D67). */
    suspend fun sync(api: NodeApi) = apply(api.newsList())

    /** Stores new announcements and applies deletions; at most one notification update per call (D67). */
    suspend fun apply(records: List<Record>) = mutex.withLock {
        if (records.isEmpty()) return@withLock
        val newsKeys = keys.newsKeys()
        val ctx = sigs.sigContext() // D109
        val now = clock.now()
        val language = Locale.getDefault().language
        var notify: Long? = null
        var removed = false
        var ignored = 0
        db.withTransaction {
            for (r in records) {
                when (val n = AnnouncementVerifier.verify(r, newsKeys, ctx)) {
                    is News.Item -> {
                        val a = n.announcement
                        val cid = db.conversationDao().getOrCreateNews(n.createdMs) // dated by the news, not by its arrival
                        val id = db.messageDao().insert(MessageEntity(messageId = n.key, conversationId = cid, senderId = n.author,
                            outgoing = false, sentMs = n.createdMs, receivedMs = now,
                            body = NewsText.pick(a.textRu, a.textEn, language),
                            status = MessageStatus.DELIVERED, kind = MessageKind.NEWS, systemData = a.toByteArray()))
                        if (id > 0) {
                            db.conversationDao().touch(cid, n.createdMs)
                            db.conversationDao().incrementUnread(cid)
                            if (n.createdMs > now - NOTIFY_WINDOW_MS) notify = cid
                        }
                    }
                    is News.Deleted -> if (db.messageDao().deleteNews(n.target) > 0) removed = true
                    null -> ignored++
                }
            }
            fixUnread()
        }
        if (ignored > 0) log.info("$ignored news record(s) ignored (unknown or revoked key, or malformed)")
        if (removed) clearNotification()
        notify?.let { alerts.onIncoming(it) } // after the commit: the notifier reads the stored messages
    }

    /** After a manifest update: drop news signed by keys no longer listed (D65). */
    suspend fun purgeRevoked() = mutex.withLock {
        val allowed = keys.newsKeys().map { Ids.id16(it).toList() }.toSet()
        var removed = false
        db.withTransaction {
            for (m in db.messageDao().allNews()) if (m.senderId.toList() !in allowed && db.messageDao().deleteNews(m.messageId) > 0) removed = true
            fixUnread()
        }
        if (removed) clearNotification()
    }

    /**
     * A deleted or revoked announcement must not stay readable in the notification shade: the news notification goes
     * (re-posting the rest would alert again; the chat list still shows the unread count).
     */
    private suspend fun clearNotification() { db.conversationDao().news()?.let { alerts.onRead(it.id) } }

    /** unread never exceeds the number of news messages left (deletions may remove unread ones). */
    private suspend fun fixUnread() {
        val c = db.conversationDao().news() ?: return
        val left = db.messageDao().count(c.id)
        if (c.unread > left) db.conversationDao().setUnread(c.id, left)
    }

    companion object {
        /** Only announcements created within this window notify (a new member gets no notification storm, D67). */
        const val NOTIFY_WINDOW_MS = 3 * 86_400_000L
        private val log = Logger.getLogger("NewsRepository")
    }
}
