package app.ottermsg.core.data

import app.ottermsg.core.data.messaging.Outbox
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutboxTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)

    @After fun tearDown() { a.close(); b.close() }

    /** A and B registered; only B has a session object, which the tests drive by hand (no workers). */
    private suspend fun setup(): Pair<Long, FakeNode.Session> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        b.register(a.invites.create().link, "Боря", "borya").getOrThrow()
        a.offline()
        val s = node.connect()
        s.login(b.me.userId, b.me.accountKey)
        return b.messages.conversationWith(a.me.userId) to s
    }

    @Test fun transientErrorBacksOffAndKeepsRecord() = runBlocking {
        val (cid, s) = setup()
        val local = b.messages.sendText(cid, "hi")!!
        node.failSend = Status.STATUS_RETRY_LATER // transient: prekeys fetched, record built, send refused
        b.outbox.drainOnce(s)
        val item = b.db.outboxDao().due(Long.MAX_VALUE).single()
        assertEquals(1, item.attempts)
        assertTrue(item.nextAttemptMs > clock.now())
        val record = item.recordBytes!! // encrypted once and persisted before the first send
        assertEquals(MessageStatus.PENDING, b.db.messageDao().get(local)!!.status)

        node.failSend = null
        node.reachable = false
        clock.advance(Outbox.MAX_BACKOFF_MS)
        b.outbox.drainOnce(s) // unreachable: transient again
        assertEquals(2, b.db.outboxDao().due(Long.MAX_VALUE).single().attempts)
        assertTrue(record.contentEquals(b.db.outboxDao().due(Long.MAX_VALUE).single().recordBytes))

        node.reachable = true
        b.outbox.drainOnce(s) // not due yet
        assertEquals(1, b.db.outboxDao().due(Long.MAX_VALUE).size)
        clock.advance(Outbox.MAX_BACKOFF_MS)
        b.outbox.drainOnce(s)
        assertTrue(b.db.outboxDao().due(Long.MAX_VALUE).isEmpty())
        assertEquals(MessageStatus.SENT, b.db.messageDao().get(local)!!.status)
        assertEquals(1, node.mailbox(a.me.userId).size)
    }

    @Test fun permanentErrorFailsThenRetryRequeues() = runBlocking {
        val (cid, s) = setup()
        val local = b.messages.sendText(cid, "hi")!!
        node.failSend = Status.STATUS_INVALID
        b.outbox.drainOnce(s)
        assertEquals(MessageStatus.FAILED, b.db.messageDao().get(local)!!.status)
        assertTrue(b.db.outboxDao().due(Long.MAX_VALUE).isEmpty())

        node.failSend = null
        assertTrue(b.messages.retry(local))
        assertEquals(MessageStatus.PENDING, b.db.messageDao().get(local)!!.status)
        b.outbox.drainOnce(s)
        assertEquals(MessageStatus.SENT, b.db.messageDao().get(local)!!.status)
        assertTrue(!b.messages.retry(local)) // only FAILED messages can be retried
    }

    @Test fun backoffCapsAtFiveMinutes() = runBlocking {
        val (cid, s) = setup()
        b.messages.sendText(cid, "hi")
        node.reachable = false
        repeat(12) {
            clock.advance(Outbox.MAX_BACKOFF_MS)
            b.outbox.drainOnce(s)
        }
        val item = b.db.outboxDao().due(Long.MAX_VALUE).single()
        assertTrue(item.nextAttemptMs - clock.now() <= Outbox.MAX_BACKOFF_MS)
        assertEquals(12, item.attempts)
    }
}
