package app.ottermsg.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.OutboxEntity
import app.ottermsg.core.database.entity.SeenEnvelopeEntity
import app.ottermsg.core.database.entity.SettingEntity
import app.ottermsg.core.database.entity.SignalKvEntity
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessagingDaoTest {
    private lateinit var db: OtterDatabase
    private val alice = ByteArray(16) { 1 }
    private val bob = ByteArray(16) { 2 }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun msg(id: Byte, cid: Long, outgoing: Boolean, sentMs: Long, status: Int = MessageStatus.PENDING, body: String = "m$id") =
        MessageEntity(messageId = ByteArray(16) { id }, conversationId = cid, senderId = if (outgoing) alice else bob,
            outgoing = outgoing, sentMs = sentMs, receivedMs = sentMs, body = body, status = status)

    @Test fun getOrCreateDirectIsIdempotent() = runTest {
        val c = db.conversationDao()
        val a = c.getOrCreateDirect(bob, "Bob", 1)
        val b = c.getOrCreateDirect(bob, "Bob again", 2)
        assertEquals(a, b)
        assertEquals("Bob", c.get(a)!!.title)
    }

    @Test fun duplicateMessageIgnored() = runTest {
        val cid = db.conversationDao().getOrCreateDirect(bob, "Bob", 1)
        assertTrue(db.messageDao().insert(msg(1, cid, false, 10)) > 0)
        assertEquals(-1L, db.messageDao().insert(msg(1, cid, false, 11)))
    }

    @Test fun statusOnlyMovesForward() = runTest {
        val m = db.messageDao()
        val cid = db.conversationDao().getOrCreateDirect(bob, "Bob", 1)
        val out = m.insert(msg(1, cid, true, 10))
        val id = ByteArray(16) { 1 }
        assertEquals(1, m.advanceStatus(id, MessageStatus.DELIVERED))
        assertEquals(0, m.advanceStatus(id, MessageStatus.SENT)) // never backwards
        assertEquals(1, m.advanceStatus(id, MessageStatus.READ))
        assertEquals(MessageStatus.READ, m.get(out)!!.status)

        val failed = m.insert(msg(2, cid, true, 11))
        m.markFailed(failed)
        assertEquals(0, m.advanceStatus(ByteArray(16) { 2 }, MessageStatus.DELIVERED))
        assertEquals(0, m.advanceStatusLocal(failed, MessageStatus.SENT))
        assertEquals(MessageStatus.FAILED, m.get(failed)!!.status)

        val incoming = m.insert(msg(3, cid, false, 12, MessageStatus.DELIVERED))
        assertEquals(0, m.advanceStatus(ByteArray(16) { 3 }, MessageStatus.READ))
        assertEquals(MessageStatus.DELIVERED, m.get(incoming)!!.status)
    }

    @Test fun summariesNewestFirstWithLastMessage() = runTest {
        val c = db.conversationDao()
        val m = db.messageDao()
        val withBob = c.getOrCreateDirect(bob, "Bob", 100)
        val withCarol = c.getOrCreateDirect(ByteArray(16) { 3 }, "Carol", 200)
        m.insert(msg(1, withBob, false, 300, body = "old"))
        m.insert(msg(2, withBob, true, 400, MessageStatus.SENT, body = "newest"))
        c.touch(withBob, 400)
        c.incrementUnread(withBob)
        c.incrementUnread(withBob)
        val s = c.observeSummaries().first()
        assertEquals(listOf("Bob", "Carol"), s.map { it.title })
        assertEquals("newest", s[0].lastBody)
        assertEquals(true, s[0].lastOutgoing)
        assertEquals(MessageStatus.SENT, s[0].lastStatus)
        assertEquals(2, s[0].unread)
        assertNull(s[1].lastBody)
        c.touch(withBob, 50) // touch never moves activity backwards
        assertEquals(400L, c.get(withBob)!!.lastActivityMs)
        c.clearUnread(withBob)
        assertEquals(0, c.get(withBob)!!.unread)
    }

    @Test fun readReceiptsPending() = runTest {
        val m = db.messageDao()
        val cid = db.conversationDao().getOrCreateDirect(bob, "Bob", 1)
        val a = m.insert(msg(1, cid, false, 10))
        m.insert(msg(2, cid, true, 11))
        assertEquals(listOf(a), m.unreadReceiptsPending(cid).map { it.localId })
        m.markReadReceiptSent(listOf(a))
        assertTrue(m.unreadReceiptsPending(cid).isEmpty())
    }

    @Test fun seenEnvelopes() = runTest {
        val s = db.seenEnvelopeDao()
        val key = ByteArray(32) { 7 }
        assertTrue(s.insert(SeenEnvelopeEntity(key, 100)) > 0)
        assertEquals(-1L, s.insert(SeenEnvelopeEntity(key, 200)))
        s.prune(now = 150)
        assertTrue(s.insert(SeenEnvelopeEntity(key, 300)) > 0) // pruned, so new again
    }

    @Test fun outboxDueOrdering() = runTest {
        val o = db.outboxDao()
        val a = o.insert(OutboxEntity(recipientUserId = bob, contentBytes = byteArrayOf(1), messageLocalId = null, urgent = true, createdMs = 1))
        val b = o.insert(OutboxEntity(recipientUserId = bob, contentBytes = byteArrayOf(2), messageLocalId = null, urgent = false, createdMs = 2))
        o.reschedule(a, 1, 500)
        assertEquals(listOf(b), o.due(100).map { it.id })
        assertEquals(listOf(a, b), o.due(500).map { it.id })
        assertEquals(0L, o.nextAttemptAt())
        o.setRecord(b, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), o.due(500).last().recordBytes)
        o.delete(a); o.delete(b)
        assertNull(o.nextAttemptAt())
    }

    @Test fun signalKvAndSettings() = runTest {
        val kv = db.signalKvDao()
        kv.put(SignalKvEntity(5, "a.1", byteArrayOf(1)))
        kv.put(SignalKvEntity(5, "a.1", byteArrayOf(2)))
        kv.put(SignalKvEntity(1, "a", byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(2), kv.get(5, "a.1"))
        assertEquals(listOf("a.1"), kv.keys(5))
        kv.delete(5, "a.1")
        assertNull(kv.get(5, "a.1"))
        db.settingsDao().put(SettingEntity("x", byteArrayOf(4)))
        assertArrayEquals(byteArrayOf(4), db.settingsDao().get("x"))
    }

    @Test fun outboxResendRule() = runTest {
        val o = db.outboxDao()
        val day = app.ottermsg.core.database.dao.OutboxDao.RESEND_AFTER_MS
        val msg = o.insert(OutboxEntity(recipientUserId = bob, contentBytes = byteArrayOf(1), messageLocalId = 7, urgent = true, createdMs = 1))
        o.markSent(msg, 1_000)
        assertTrue(o.due(1_000 + day - 1).isEmpty()) // sent, receipt not yet overdue
        assertEquals(1_000 + day, o.nextAttemptAt())
        assertEquals(listOf(msg), o.due(1_000 + day).map { it.id })
        o.markResent(msg)
        assertTrue(o.due(Long.MAX_VALUE / 2).isEmpty()) // resent once only
        assertNull(o.nextAttemptAt())
        o.deleteSentBefore(1_001)
        assertNull(o.due(0).firstOrNull())
        assertEquals(0, o.due(Long.MAX_VALUE / 2).size)

        val again = o.insert(OutboxEntity(recipientUserId = bob, contentBytes = byteArrayOf(2), messageLocalId = 8, urgent = true, createdMs = 2))
        o.deleteForMessage(8)
        assertTrue(o.due(Long.MAX_VALUE / 2).none { it.id == again })
    }

    @Test fun manifestsAndEndpointStats() = runTest {
        val m = db.manifestDao()
        m.insert(app.ottermsg.core.database.entity.ManifestEntity(1, byteArrayOf(1), receivedMs = 10))
        m.insert(app.ottermsg.core.database.entity.ManifestEntity(3, byteArrayOf(3), receivedMs = 30))
        m.insert(app.ottermsg.core.database.entity.ManifestEntity(2, byteArrayOf(2), receivedMs = 20))
        assertEquals(listOf(3L, 2L), m.latestTwo().map { it.version })
        assertEquals(30L, m.latestTwo()[0].receivedMs)

        val e = db.endpointStatDao()
        assertNull(e.get("k"))
        e.upsert(app.ottermsg.core.database.entity.EndpointStatEntity("k", lastSuccessMs = 5, rttMs = 40))
        e.upsert(app.ottermsg.core.database.entity.EndpointStatEntity("k", lastSuccessMs = 6, rttMs = 30, failures = 1))
        assertEquals(30L, e.get("k")!!.rttMs)
        assertEquals(1, e.all().size)
    }
}
