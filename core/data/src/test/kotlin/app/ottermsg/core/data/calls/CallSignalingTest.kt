package app.ottermsg.core.data.calls

import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.proto.v1.CallAnswer
import app.ottermsg.proto.v1.CallHangup
import app.ottermsg.proto.v1.CallMessage
import app.ottermsg.proto.v1.CallOffer
import app.ottermsg.proto.v1.IceCandidate
import app.ottermsg.proto.v1.IceCandidates
import app.ottermsg.proto.v1.ReceiptMessage
import app.ottermsg.proto.v1.RecordBody
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Records what MailboxReceiver hands to the call layer. */
class RecordingInbox(private val callIds: Set<String> = emptySet()) : CallInbox {
    val messages = mutableListOf<Triple<ByteArray, String, CallMessage>>()
    val receipts = mutableListOf<ReceiptMessage>()
    override suspend fun onCallMessage(from: ByteArray, name: String, msg: CallMessage, createdMs: Long) { messages += Triple(from, name, msg) }
    override fun isCallReceipt(r: ReceiptMessage) = r.messageIdsList.any { CallId.of(it.toByteArray()).hex in callIds }
    override suspend fun onCallReceipt(from: ByteArray, r: ReceiptMessage) { receipts += r }
}

@RunWith(RobolectricTestRunner::class)
class CallSignalingTest {
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val callId = CallId.random()
    private val aInbox = RecordingInbox()
    private val bInbox = RecordingInbox(setOf(callId.hex))
    private val a = TestGraph(node, clock, calls = aInbox)
    private val b = TestGraph(node, clock, calls = bInbox)

    @After fun tearDown() { a.close(); b.close() }

    /** A and B registered, no workers: both sessions are driven by hand. */
    private suspend fun setup(): Pair<FakeNode.Session, FakeNode.Session> {
        a.register(node.bootstrapLink(), "Мама", "mama").getOrThrow()
        a.online()
        b.register(a.invites.create().link, "Боря", "borya").getOrThrow()
        a.offline()
        val sa = node.connect().also { it.login(a.me.userId, a.me.accountKey) }
        val sb = node.connect().also { it.login(b.me.userId, b.me.accountKey) }
        b.directory.sync(sb) // the inviter from the link is used only once the directory confirms it (C23)
        a.directory.sync(sa) // not left to the background sync that a.offline() may have cut short
        return sa to sb
    }

    @Test fun offerGoesOutUrgentWithTwoMinuteExpiryAndReachesTheInboxWithoutAChat() = runBlocking {
        val (sa, sb) = setup()
        assertNull(a.db.conversationDao().byPeer(b.me.userId))
        val offer = CallMessage.newBuilder().setCallId(ByteString.copyFrom(callId.bytes()))
            .setOffer(CallOffer.newBuilder().setSdp("v=0")).build()
        val before = clock.now()
        CallSignaling(b.outbox, clock).send(a.me.userId, offer)

        val item = b.db.outboxDao().due(Long.MAX_VALUE).single()
        assertTrue(item.urgent)
        assertNull(item.messageLocalId)
        assertTrue(item.expiresMs in before + CallSignaling.EXPIRY_MS..clock.now() + CallSignaling.EXPIRY_MS)

        b.outbox.drainOnce(sb)
        assertTrue(b.db.outboxDao().due(Long.MAX_VALUE).isEmpty()) // not kept for the 24 h resend
        val rec = node.mailbox(a.me.userId).single()
        assertEquals(item.expiresMs, RecordBody.parseFrom(rec.body).expiresMs)

        a.receiver.handle(sa, node.mailbox(a.me.userId))
        val (from, name, msg) = aInbox.messages.single()
        assertTrue(from.contentEquals(b.me.userId))
        assertEquals("Боря", name)
        assertEquals(offer, msg)
        assertTrue("acked", node.mailbox(a.me.userId).isEmpty())
        assertNull("no chat created", a.db.conversationDao().byPeer(b.me.userId))
        assertTrue(a.db.messageDao().all().isEmpty())
    }

    /** Only the offer wakes the callee: nodes count every urgent short-lived envelope against the call-wake budget (D108). */
    @Test fun onlyTheOfferIsUrgentEveryOtherCallMessageIsNotAndAllExpireInTwoMinutes() = runBlocking {
        setup()
        fun m() = CallMessage.newBuilder().setCallId(ByteString.copyFrom(callId.bytes()))
        val sent = listOf(
            m().setOffer(CallOffer.newBuilder().setSdp("v=0")).build() to true,
            m().setOffer(CallOffer.newBuilder().setSdp("v=0").setRestart(true)).build() to true,
            m().setAnswer(CallAnswer.newBuilder().setSdp("v=0")).build() to false,
            m().setCandidates(IceCandidates.newBuilder().addCandidates(
                IceCandidate.newBuilder().setSdpMid("0").setSdpMlineIndex(0).setCandidate("candidate:x"))).build() to false,
            m().setHangup(CallHangup.newBuilder().setReason(CallHangup.Reason.NORMAL)).build() to false,
            m().build() to false, // a kind this build does not know yet
        )
        val signaling = CallSignaling(b.outbox, clock)
        for ((msg, urgent) in sent) {
            val before = clock.now()
            signaling.send(a.me.userId, msg)
            val item = b.db.outboxDao().due(Long.MAX_VALUE).last()
            assertEquals(msg.kindCase.name, urgent, item.urgent)
            assertTrue(msg.kindCase.name, item.expiresMs in before + CallSignaling.EXPIRY_MS..clock.now() + CallSignaling.EXPIRY_MS)
        }
        assertEquals(sent.size, b.db.outboxDao().due(Long.MAX_VALUE).size)
    }

    /** The caller cancels while the callee sleeps: the offer's push wakes it, the hangup (no push) comes in the same fetch. */
    @Test fun aHangupWithoutAPushArrivesInTheFetchTheOfferWokeAndAfterIt() = runBlocking {
        val (sa, sb) = setup()
        val signaling = CallSignaling(b.outbox, clock)
        val offer = CallMessage.newBuilder().setCallId(ByteString.copyFrom(callId.bytes()))
            .setOffer(CallOffer.newBuilder().setSdp("v=0")).build()
        val hangup = CallMessage.newBuilder().setCallId(ByteString.copyFrom(callId.bytes()))
            .setHangup(CallHangup.newBuilder().setReason(CallHangup.Reason.NORMAL)).build()
        signaling.send(a.me.userId, offer)
        clock.advance(3_000)
        signaling.send(a.me.userId, hangup)
        assertEquals(listOf(true, false), b.db.outboxDao().due(Long.MAX_VALUE).map { it.urgent })
        b.outbox.drainOnce(sb)

        a.receiver.handle(sa, node.mailbox(a.me.userId))
        assertEquals(listOf(offer, hangup), aInbox.messages.map { it.third })
        assertTrue("acked", node.mailbox(a.me.userId).isEmpty())
    }

    @Test fun deliveredReceiptForACallIsNonUrgentAndGoesToTheInboxOnly() = runBlocking {
        val (sa, sb) = setup()
        CallSignaling(a.outbox, clock).delivered(b.me.userId, callId.bytes())
        val item = a.db.outboxDao().due(Long.MAX_VALUE).single()
        assertFalse(item.urgent)
        assertTrue(item.expiresMs > clock.now())
        a.outbox.drainOnce(sa)

        b.receiver.handle(sb, node.mailbox(b.me.userId))
        val r = bInbox.receipts.single()
        assertEquals(ReceiptMessage.Type.DELIVERED, r.type)
        assertEquals(callId, CallId.of(r.getMessageIds(0).toByteArray()))
        assertTrue("acked", node.mailbox(b.me.userId).isEmpty())
        assertNull("no chat created", b.db.conversationDao().byPeer(a.me.userId))
    }

    @Test fun expiredSignalingIsNeverSent() = runBlocking {
        val (_, sb) = setup()
        CallSignaling(b.outbox, clock).send(a.me.userId, CallMessage.newBuilder().setCallId(ByteString.copyFrom(callId.bytes())).build())
        clock.advance(CallSignaling.EXPIRY_MS)
        b.outbox.drainOnce(sb)
        assertEquals(0, node.sends)
        assertTrue(b.db.outboxDao().due(Long.MAX_VALUE).isEmpty())
    }

    @Test fun callContentFromARevokedContactNeverRings() = runBlocking {
        val (sa, sb) = setup()
        val c = checkNotNull(a.db.contactDao().get(b.me.userId))
        a.db.contactDao().upsert(c.copy(revoked = true))
        CallSignaling(b.outbox, clock).send(a.me.userId, CallMessage.newBuilder().setCallId(ByteString.copyFrom(callId.bytes()))
            .setOffer(CallOffer.newBuilder().setSdp("v=0")).build())
        b.outbox.drainOnce(sb)
        assertEquals(1, node.mailbox(a.me.userId).size)
        a.receiver.handle(sa, node.mailbox(a.me.userId))
        assertTrue(aInbox.messages.isEmpty())
        assertTrue("acked", node.mailbox(a.me.userId).isEmpty())
    }
}
