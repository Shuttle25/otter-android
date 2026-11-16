package app.ottermsg.core.data.news

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.Ids
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.crypto.SigContext
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.push.MessageAlerts
import app.ottermsg.core.data.sync.NewsKeys
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Announcement
import app.ottermsg.proto.v1.AnnouncementDeleted
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class NewsRepositoryTest {
    private val clock = TestClock()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build()
    private val key = Ed25519.keyPairFromSeed(ByteArray(32) { 3 })
    private val other = Ed25519.keyPairFromSeed(ByteArray(32) { 4 })
    @Volatile private var allowed: List<ByteArray> = listOf(key.publicKey)
    private val incoming: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val cleared: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val alerts = object : MessageAlerts {
        override suspend fun onIncoming(conversationId: Long) { incoming += conversationId }
        override fun onRead(conversationId: Long) { cleared += conversationId }
    }
    private val repo = NewsRepository(db, object : NewsKeys { override suspend fun newsKeys() = allowed }, clock, alerts,
        object : app.ottermsg.core.data.sync.SigContexts { override suspend fun sigContext() = SigContext(ByteArray(16), 0) })
    private val savedLocale = Locale.getDefault()

    @After fun tearDown() { db.close(); Locale.setDefault(savedLocale) }

    private fun item(ru: String, en: String = "", k: Ed25519.KeyPair = key, createdMs: Long = clock.now()): Record =
        RecordCodec.sign(RecordCodec.body(RecordType.ANNOUNCEMENT, Ids.id16(k.publicKey),
            Announcement.newBuilder().setTextRu(ru).setTextEn(en).build().toByteArray(), createdMs = createdMs), k, SigContext(ByteArray(16), 0))

    private fun deletion(target: Record): Record {
        val b = app.ottermsg.proto.v1.RecordBody.parseFrom(target.body)
        val t = b.author.toByteArray() + b.id.toByteArray()
        return RecordCodec.sign(RecordCodec.body(RecordType.ANNOUNCEMENT_DELETED, Ids.id16(key.publicKey),
            AnnouncementDeleted.newBuilder().setTarget(ByteString.copyFrom(t)).build().toByteArray()), key, SigContext(ByteArray(16), 0))
    }

    @Test fun newItemBecomesAnUnreadMessageAndNotifiesOnce() = runBlocking {
        Locale.setDefault(Locale("ru"))
        val r = item("Привет", "Hello")
        repo.apply(listOf(r, item("Второе")))
        val c = db.conversationDao().news()!!
        assertEquals(ConversationType.NEWS, c.type)
        assertEquals("", c.title)
        assertEquals(2, c.unread)
        val m = db.messageDao().allNews()
        assertEquals(listOf("Привет", "Второе"), m.map { it.body })
        val first = m[0]
        assertEquals(MessageKind.NEWS, first.kind)
        assertEquals(false, first.outgoing)
        assertEquals(MessageStatus.DELIVERED, first.status)
        assertArrayEquals(Ids.id16(key.publicKey), first.senderId)
        assertEquals(32, first.messageId.size)
        assertEquals("Hello", Announcement.parseFrom(first.systemData).textEn)
        assertEquals(listOf(c.id), incoming.toList()) // one notification update per apply
        assertTrue(c.lastActivityMs >= maxOf(m[0].sentMs, m[1].sentMs))

        repo.apply(listOf(r)) // duplicate
        assertEquals(2, db.messageDao().count(c.id))
        assertEquals(2, db.conversationDao().news()!!.unread)
        assertEquals(1, incoming.size)
    }

    @Test fun newMemberChatIsDatedByTheNewestNewsNotByItsArrival() = runBlocking {
        val day = 86_400_000L
        val yesterday = clock.now() - day
        repo.apply(listOf(item("Позавчера", createdMs = yesterday - day), item("Вчера", createdMs = yesterday)))
        // the chat list sorts and dates by last_activity_ms: a member joining today must not see yesterday's news as new
        assertEquals(yesterday, db.conversationDao().news()!!.lastActivityMs)
    }

    @Test fun englishPhoneGetsEnglishBody() = runBlocking {
        Locale.setDefault(Locale.ENGLISH)
        repo.apply(listOf(item("Привет", "Hello")))
        assertEquals("Hello", db.messageDao().allNews().single().body)
    }

    @Test fun oldItemIsStoredWithoutNotification() = runBlocking {
        repo.apply(listOf(item("Давно", createdMs = clock.now() - NewsRepository.NOTIFY_WINDOW_MS - 1)))
        assertEquals(1, db.messageDao().allNews().size)
        assertTrue(incoming.isEmpty())
    }

    @Test fun deletionRemovesTheMessage() = runBlocking {
        val r = item("Ошибка")
        repo.apply(listOf(r, item("Остаётся")))
        repo.apply(listOf(deletion(r)))
        assertEquals(listOf("Остаётся"), db.messageDao().allNews().map { it.body })
        assertEquals(1, db.conversationDao().news()!!.unread) // never more than the messages left
        // a deletion that arrives with (after) its target in one batch
        val r2 = item("Сразу удалено")
        repo.apply(listOf(r2, deletion(r2)))
        assertEquals(listOf("Остаётся"), db.messageDao().allNews().map { it.body })
    }

    @Test fun removedNewsTakesItsNotificationAlong() = runBlocking {
        val r = item("Ошибка")
        repo.apply(listOf(r))
        val cid = db.conversationDao().news()!!.id
        assertTrue(cleared.isEmpty())
        repo.apply(listOf(deletion(r))) // a deleted announcement must not stay readable in the notification shade
        assertEquals(listOf(cid), cleared.toList())
        repo.apply(listOf(deletion(r))) // nothing removed this time
        assertEquals(1, cleared.size)
        repo.apply(listOf(item("Новое")))
        allowed = emptyList()
        repo.purgeRevoked()
        assertEquals(listOf(cid, cid), cleared.toList())
        repo.purgeRevoked()
        assertEquals(2, cleared.size)
    }

    @Test fun revokedKeyIsPurged() = runBlocking {
        val k2 = Ed25519.keyPairFromSeed(ByteArray(32) { 5 })
        allowed = listOf(key.publicKey, k2.publicKey)
        repo.apply(listOf(item("Старый ключ"), item("Новый ключ", k = k2)))
        allowed = listOf(k2.publicKey)
        repo.purgeRevoked()
        assertEquals(listOf("Новый ключ"), db.messageDao().allNews().map { it.body })
        assertEquals(1, db.conversationDao().news()!!.unread)
        allowed = emptyList()
        repo.purgeRevoked()
        assertTrue(db.messageDao().allNews().isEmpty())
        assertEquals(0, db.conversationDao().news()!!.unread)
    }

    @Test fun unknownKeyIsIgnored() = runBlocking {
        repo.apply(listOf(item("Чужой", k = other)))
        assertNull(db.conversationDao().news())
        assertTrue(incoming.isEmpty())
    }
}
