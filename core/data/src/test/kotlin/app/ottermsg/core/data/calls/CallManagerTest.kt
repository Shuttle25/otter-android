package app.ottermsg.core.data.calls

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.network.FailureClass
import app.ottermsg.core.network.NetLog
import app.ottermsg.core.network.NetLogEntry
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.CallAnswer
import app.ottermsg.proto.v1.CallHangup
import app.ottermsg.proto.v1.CallMessage
import app.ottermsg.proto.v1.CallOffer
import app.ottermsg.proto.v1.IceCandidate
import app.ottermsg.proto.v1.IceCandidates
import app.ottermsg.proto.v1.ReceiptMessage
import app.ottermsg.proto.v1.Status
import app.ottermsg.proto.v1.TurnCredentials
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

class FakeEngine : MediaEngine {
    var opened: Pair<List<IceServerSpec>, Boolean>? = null; var listener: MediaListener? = null
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf()); @Volatile var closed = false
    override suspend fun open(servers: List<IceServerSpec>, relayOnly: Boolean, listener: MediaListener) { opened = servers to relayOnly; this.listener = listener }
    override suspend fun offer(restart: Boolean) = "offer-sdp".also { calls += "offer:$restart" }
    override suspend fun answer(remoteOffer: String) = "answer-sdp".also { calls += "answer:$remoteOffer" }
    override suspend fun applyAnswer(sdp: String) { calls += "apply:$sdp" }
    override fun addCandidates(c: List<Candidate>) { calls += "cands:${c.size}" }
    override fun setMuted(muted: Boolean) { calls += "muted:$muted" }
    @Volatile var livePath: PathInfo? = PathInfo("relay-udp", 40) // null = no selected pair (e.g. the peer already closed)
    override suspend fun path() = livePath
    override fun close() { closed = true }
}

class RecordingTransport : CallTransport {
    val sent: MutableList<Pair<ByteArray, CallMessage>> = Collections.synchronizedList(mutableListOf())
    val delivered: MutableList<CallId> = Collections.synchronizedList(mutableListOf())
    override suspend fun send(peer: ByteArray, msg: CallMessage) { sent += peer to msg }
    override suspend fun delivered(peer: ByteArray, callId: ByteArray) { delivered += CallId.of(callId) }
    fun kinds() = synchronized(sent) { sent.map { it.second.kindCase } }
}

/** A node connection with only nodeId() and turnCredentials() in use. */
class TurnApi(@Volatile var turn: TurnCredentials?) : NodeApi by FakeNode({ 0L }).connect() {
    override suspend fun nodeId() = ByteArray(16) { 7 }
    override suspend fun turnCredentials() = turn ?: throw RpcException(Status.STATUS_UNAVAILABLE, "turn off")
}

class FakeLink(val api: NodeApi?, val label: String? = "ru-1") : CallLink {
    val leases: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    override suspend fun api(timeoutMs: Long) = api
    override fun nodeLabel() = label
    override fun acquire(tag: String) { leases += tag }
    override fun release(tag: String) { leases -= tag }
}

class CallNetLog : NetLog {
    val entries: MutableList<NetLogEntry> = Collections.synchronizedList(mutableListOf())
    override suspend fun add(e: NetLogEntry) { entries += e }
}

class RecordingAlerts : CallAlerts {
    val incoming: MutableList<CallState.Incoming> = Collections.synchronizedList(mutableListOf())
    val missed: MutableList<Pair<Peer, Long>> = Collections.synchronizedList(mutableListOf())
    @Volatile var released = 0
    override fun incoming(call: CallState.Incoming) { incoming += call }
    override fun missed(peer: Peer, conversationId: Long) { missed += peer to conversationId }
    override fun released() { released++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CallManagerTest {
    private val test = TestScope(StandardTestDispatcher())
    private val job = Job()
    private val scope = CoroutineScope(test.coroutineContext.minusKey(Job) + job)
    private val clock = Clock { 1_800_000_000_000L + test.testScheduler.currentTime }
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build()
    private val settings = Settings(db.settingsDao())
    private val turn = TurnCredentials.newBuilder().setUsername("${clock.now() / 1000 + 43_200}:abcd").setPassword("pw").setTtlS(43_200)
        .addUrls("turn:203.0.113.7:3478?transport=udp").addUrls("turn:203.0.113.7:3478?transport=tcp")
        .addUrls("turns:203.0.113.7:5349?transport=tcp").build()
    private val api = TurnApi(turn)
    private val link = FakeLink(api)
    private val transport = RecordingTransport()
    private val alerts = RecordingAlerts()
    private val netLog = CallNetLog()
    private val engines: MutableList<FakeEngine> = Collections.synchronizedList(mutableListOf())
    private val manager = CallManager(transport, CallLines(db, clock), { FakeEngine().also { engines += it } }, TurnCache(clock),
        link, settings, alerts, netLog, clock, scope)
    private val peer = Peer.of(ByteArray(16) { 5 }, "Анна")

    @After fun tearDown() { job.cancel(); test.cancel(); db.close() }

    /** Runs the virtual-time queue until [cond] holds (Room finishes on its own threads). */
    private fun settle(what: String = "condition", cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000 // the first Room access of a run can take seconds
        while (System.currentTimeMillis() < deadline) {
            test.runCurrent()
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun msg(id: CallId) = CallMessage.newBuilder().setCallId(ByteString.copyFrom(id.bytes()))
    private fun deliver(m: CallMessage, createdMs: Long = clock.now()) = runBlocking { manager.onCallMessage(peer.id, peer.name, m, createdMs) }
    private fun engine() = engines.last()
    private fun callLines() = runBlocking { db.messageDao().all().filter { it.kind == MessageKind.CALL } }

    private fun dialToOutgoing(): CallState.Outgoing {
        manager.dial(peer)
        settle("offer sent") { transport.sent.isNotEmpty() }
        return manager.state.value as CallState.Outgoing
    }

    @Test fun dialOpensTheEngineWithTheNodeRelayAndSendsTheOffer() {
        val out = dialToOutgoing()
        val (servers, relayOnly) = engine().opened!!
        assertEquals(3, servers.single().urls.size)
        assertEquals("pw", servers.single().password)
        assertTrue("relay-only by default", relayOnly)
        val (to, m) = transport.sent.single()
        assertTrue(to.contentEquals(peer.id))
        assertEquals("offer-sdp", m.offer.sdp)
        assertFalse(m.offer.restart)
        assertEquals(out.id, CallId.of(m.callId.toByteArray()))
        assertTrue(CallManager.LEASE in link.leases)

        // the DELIVERED receipt for this offer is a call receipt, synchronously
        val r = ReceiptMessage.newBuilder().setType(ReceiptMessage.Type.DELIVERED).addMessageIds(m.callId).build()
        assertTrue(manager.isCallReceipt(r))
        runBlocking { manager.onCallReceipt(peer.id, r) }
        settle("ringing") { (manager.state.value as? CallState.Outgoing)?.ringing == true }
    }

    @Test fun relayOnlyOffFollowsTheSetting() {
        runBlocking { settings.setCallsRelayOnly(false) }
        dialToOutgoing()
        assertFalse(engine().opened!!.second)
    }

    @Test fun answerAppliedThenIceConnectedMakesTheCallActive() {
        val out = dialToOutgoing()
        deliver(msg(out.id).setAnswer(CallAnswer.newBuilder().setSdp("answer-sdp")).build())
        settle("answer applied") { "apply:answer-sdp" in engine().calls }
        engine().listener!!.onIce(IcePhase.CONNECTED)
        settle("active") { manager.state.value is CallState.Active }
    }

    @Test fun hangUpSendsHangupWritesTheLineAndReleases() {
        val out = dialToOutgoing()
        deliver(msg(out.id).setAnswer(CallAnswer.newBuilder().setSdp("answer-sdp")).build())
        settle("answer applied") { "apply:answer-sdp" in engine().calls }
        engine().listener!!.onIce(IcePhase.CONNECTED)
        settle("active") { manager.state.value is CallState.Active }
        test.advanceTimeBy(5_000)
        manager.hangUp()
        settle("released") { alerts.released == 1 }
        val hangup = transport.sent.last().second
        assertEquals(CallHangup.Reason.NORMAL, hangup.hangup.reason)
        val line = callLines().single()
        assertEquals(CallOutcome.OUTGOING.code, line.callOutcome)
        assertTrue(line.callDurationS in 4L..6L)
        assertTrue(engine().closed)
        assertFalse(CallManager.LEASE in link.leases)
        assertEquals(CallState.Idle, manager.state.value)
    }

    @Test fun aFinishedCallAddsOneConnectionLogLine() {
        val out = dialToOutgoing()
        deliver(msg(out.id).setAnswer(CallAnswer.newBuilder().setSdp("answer-sdp")).build())
        settle("answer applied") { "apply:answer-sdp" in engine().calls }
        engine().listener!!.onIce(IcePhase.CONNECTED)
        settle("active") { manager.state.value is CallState.Active }
        test.advanceTimeBy(65_000)
        manager.hangUp()
        settle("released") { alerts.released == 1 }
        val e = netLog.entries.single()
        assertEquals("ru-1", e.nodeLabel)
        assertEquals("call:relay-udp", e.endpoint)
        assertEquals(FailureClass.OK, e.result)
        assertEquals(40L, e.rttMs)
        assertTrue(e.detail, e.detail.matches(Regex("OUTGOING 1:0[4-6]")))
    }

    @Test fun aCallThatFailedToConnectIsLoggedAsTimeout() {
        val id = CallId.random()
        deliver(msg(id).setOffer(CallOffer.newBuilder().setSdp("their-offer")).build())
        settle("alerted") { alerts.incoming.isNotEmpty() }
        manager.accept()
        settle("answer sent") { transport.sent.isNotEmpty() }
        test.advanceTimeBy(CallTimers.ICE_CONNECT_MS + 1_500)
        settle("released") { alerts.released == 1 }
        val e = netLog.entries.single()
        assertEquals(FailureClass.TIMEOUT, e.result)
        assertTrue(e.detail, e.detail.startsWith("FAILED "))
    }

    @Test fun lateCandidatesAreBatched() {
        val out = dialToOutgoing()
        val l = engine().listener!!
        repeat(3) { l.onCandidate(Candidate("0", 0, "candidate:$it")) }
        test.advanceTimeBy(299); test.runCurrent()
        assertEquals(1, transport.sent.size)
        settle("batch") { test.advanceTimeBy(10); transport.sent.size == 2 }
        val c = transport.sent[1].second
        assertEquals(out.id, CallId.of(c.callId.toByteArray()))
        assertEquals(3, c.candidates.candidatesCount)
        l.onCandidate(Candidate("0", 0, "candidate:3"))
        settle("second batch") { test.advanceTimeBy(10); transport.sent.size == 3 }
        assertEquals(1, transport.sent[2].second.candidates.candidatesCount)
    }

    @Test fun incomingOfferIsAcknowledgedAlertedAndAnswered() {
        val id = CallId.random()
        deliver(msg(id).setOffer(CallOffer.newBuilder().setSdp("their-offer")).build())
        assertEquals(listOf(id), transport.delivered.toList())
        settle("alerted") { alerts.incoming.isNotEmpty() }
        assertEquals(id, alerts.incoming.single().id)
        assertTrue(CallManager.LEASE in link.leases)
        // the caller's late candidates arrive while ringing: kept until the answer is made
        deliver(msg(id).setCandidates(IceCandidates.newBuilder().addCandidates(
            IceCandidate.newBuilder().setSdpMid("0").setSdpMlineIndex(0).setCandidate("candidate:x"))).build())
        test.runCurrent()
        assertTrue(engines.isEmpty())
        manager.accept()
        settle("answer sent") { transport.sent.isNotEmpty() }
        assertEquals(listOf("answer:their-offer", "cands:1"), engine().calls.toList())
        val m = transport.sent.single().second
        assertEquals("answer-sdp", m.answer.sdp)
        assertEquals(id, CallId.of(m.callId.toByteArray()))
        assertTrue(manager.state.value is CallState.Connecting)
    }

    @Test fun anUnansweredIncomingCallBecomesAMissedLine() {
        deliver(msg(CallId.random()).setOffer(CallOffer.newBuilder().setSdp("their-offer")).build())
        settle("alerted") { alerts.incoming.isNotEmpty() }
        test.advanceTimeBy(CallTimers.RING_MS + 1_500)
        settle("missed") { alerts.missed.isNotEmpty() && alerts.released == 1 }
        val (p, chat) = alerts.missed.single()
        assertEquals(peer, p)
        assertEquals(chat, callLines().single().conversationId)
        assertEquals(CallOutcome.MISSED.code, callLines().single().callOutcome)
        assertFalse(CallManager.LEASE in link.leases)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun losingGlareDropsOurOwnEngineAndAnswersTheirCall() {
        dialToOutgoing()
        val mine = engine()
        val theirs = CallId("00".repeat(16)) // always the smaller id
        deliver(msg(theirs).setOffer(CallOffer.newBuilder().setSdp("their-offer")).build())
        settle("answered") { transport.sent.size == 2 }
        assertTrue(mine.closed)
        assertEquals(2, engines.size)
        assertEquals(listOf("answer:their-offer"), engine().calls.toList())
        assertEquals(theirs, CallId.of(transport.sent[1].second.callId.toByteArray()))
        assertTrue(alerts.incoming.isEmpty())
        assertEquals(0, alerts.released)
    }

    @Test fun noTurnOnTheNodeMeansNotAvailableAndNoLine() {
        api.turn = null
        val notices = mutableListOf<CallNotice>()
        scope.launch { manager.notices.collect { notices += it } }
        test.runCurrent()
        manager.dial(peer)
        settle("notice") { notices.isNotEmpty() && alerts.released == 1 }
        assertEquals(listOf<CallNotice>(CallNotice.NotAvailable), notices)
        assertEquals(CallState.Idle, manager.state.value)
        assertTrue(transport.sent.isEmpty())
        assertTrue(callLines().isEmpty())
        assertTrue(engines.isEmpty())
        assertFalse(CallManager.LEASE in link.leases)
    }

    @Test fun remoteHangupEndsTheCall() {
        val out = dialToOutgoing()
        val ended = mutableListOf<CallOutcome>()
        scope.launch { manager.ended.collect { ended += it } }
        test.runCurrent()
        deliver(msg(out.id).setHangup(CallHangup.newBuilder().setReason(CallHangup.Reason.BUSY)).build())
        settle("ended") { ended.isNotEmpty() && alerts.released == 1 }
        assertEquals(listOf(CallOutcome.BUSY), ended)
        assertNotNull(callLines().singleOrNull { it.callOutcome == CallOutcome.BUSY.code })
        assertTrue(engine().closed)
    }

    @Test fun remoteHangupAfterConnectingStillLogsThePathSeenWhileConnected() {
        val out = dialToOutgoing()
        deliver(msg(out.id).setAnswer(CallAnswer.newBuilder().setSdp("answer-sdp")).build())
        settle("answer applied") { "apply:answer-sdp" in engine().calls }
        engine().listener!!.onIce(IcePhase.CONNECTED)
        settle("active") { manager.state.value is CallState.Active }
        test.advanceTimeBy(30_000)
        test.runCurrent()
        engine().livePath = null // the peer closed its side first: no selected candidate pair any more
        deliver(msg(out.id).setHangup(CallHangup.newBuilder().setReason(CallHangup.Reason.NORMAL)).build())
        settle("released") { alerts.released == 1 }
        val e = netLog.entries.single()
        assertEquals("call:relay-udp", e.endpoint)
        assertEquals(40L, e.rttMs)
    }

    @Test fun aScreenOpenedAfterTheNoticeStillSeesItAndTheNextDialClearsIt() {
        api.turn = null
        manager.dial(peer)
        settle("released") { alerts.released == 1 }
        val late = mutableListOf<CallNotice>()
        val job = scope.launch { manager.notices.collect { late += it } } // the call screen subscribes only now
        test.runCurrent()
        assertEquals(listOf<CallNotice>(CallNotice.NotAvailable), late)
        job.cancel()
        api.turn = TurnCredentials.newBuilder().setUsername("9999999999:abcd").setPassword("p").setTtlS(43_200)
            .addUrls("turn:203.0.113.7:3478?transport=udp").build()
        manager.dial(peer)
        settle("outgoing") { manager.state.value is CallState.Outgoing }
        val next = mutableListOf<CallNotice>()
        scope.launch { manager.notices.collect { next += it } }
        test.runCurrent()
        assertTrue("an old notice must not show on the next call: $next", next.isEmpty())
    }
}
