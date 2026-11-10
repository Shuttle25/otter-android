package app.ottermsg.core.data

import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessagingFlowTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val a = TestGraph(node, clock)
    private val b = TestGraph(node, clock)

    @After fun tearDown() { a.close(); b.close() }

    private suspend fun TestGraph.thread(peer: TestGraph): List<MessageEntity> =
        messages.observeMessages(messages.conversationWith(peer.me.userId)).first()

    /** A joins with the bootstrap invite, invites B, B joins; both online. */
    private suspend fun family() {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        val link = a.invites.create().link
        b.register(link, "Боря", "borya").getOrThrow()
        b.online()
        eventually(what = "A sees B in the directory") { a.db.contactDao().get(b.me.userId) != null }
    }

    @Test fun sendDeliverRead() = runBlocking {
        family()
        // B's inviter (A) is a verified contact straight from the invite.
        assertTrue(b.db.contactDao().get(a.me.userId)!!.verified)

        val cid = b.messages.conversationWith(a.me.userId)
        val local = b.messages.sendText(cid, "  Привет  ")!!
        assertEquals("Привет", b.db.messageDao().get(local)!!.body)

        eventually(what = "A receives") { a.thread(b).any { it.body == "Привет" } }
        eventually(what = "B sees DELIVERED") { b.db.messageDao().get(local)!!.status == MessageStatus.DELIVERED }
        val aCid = a.messages.conversationWith(b.me.userId)
        assertEquals(1, a.db.conversationDao().get(aCid)!!.unread)

        a.messages.markConversationRead(aCid)
        assertEquals(0, a.db.conversationDao().get(aCid)!!.unread)
        eventually(what = "B sees READ") { b.db.messageDao().get(local)!!.status == MessageStatus.READ }

        // Receipts never generate receipts or messages: both outboxes drain, mailboxes empty, one message each side.
        eventually(what = "quiet") {
            a.db.outboxDao().due(Long.MAX_VALUE).isEmpty() && b.db.outboxDao().due(Long.MAX_VALUE).isEmpty() &&
                node.mailbox(a.me.userId).isEmpty() && node.mailbox(b.me.userId).isEmpty()
        }
        assertEquals(1, a.thread(b).size)
        assertEquals(1, b.thread(a).size)
        assertEquals(0, b.messages.sendText(cid, "   ")?.let { 1 } ?: 0) // blank is a no-op
    }

    @Test fun offlineRecipientGetsAllInOrder() = runBlocking {
        family()
        a.offline()
        val cid = b.messages.conversationWith(a.me.userId)
        for (t in listOf("раз", "два", "три")) b.messages.sendText(cid, t)
        eventually(what = "node holds 3 for A") { node.mailbox(a.me.userId).size == 3 }
        val delivered = node.mailbox(a.me.userId)

        a.online()
        eventually(what = "A got all 3") { a.thread(b).size == 3 }
        assertEquals(listOf("раз", "два", "три"), a.thread(b).map { it.body })
        eventually(what = "acked") { node.mailbox(a.me.userId).isEmpty() }

        // The same records delivered again (e.g. after a lost ACK) do not create duplicates.
        a.receiver.handle(a.session!!, delivered + delivered)
        assertEquals(3, a.thread(b).size)
        assertTrue(a.thread(b).none { it.kind == MessageKind.SYSTEM_UNDECRYPTABLE })
    }

    @Test fun repliesBothWays() = runBlocking {
        family()
        val bc = b.messages.conversationWith(a.me.userId)
        b.messages.sendText(bc, "первое")
        eventually(what = "A receives") { a.thread(b).size == 1 }
        val ac = a.messages.conversationWith(b.me.userId)
        val reply = a.messages.sendText(ac, "ответ")!!
        eventually(what = "B receives reply") { b.thread(a).any { it.body == "ответ" } }
        eventually(what = "A reply delivered") { a.db.messageDao().get(reply)!!.status == MessageStatus.DELIVERED }
    }
}
