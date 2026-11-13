package app.ottermsg.core.data.calls

import app.ottermsg.core.data.calls.CallEffect.*
import app.ottermsg.core.data.calls.CallEvent.*
import org.junit.Assert.*
import org.junit.Test

class CallStateMachineTest {
    private val anna = Peer.of(ByteArray(16) { 1 }, "Anna")
    private val boris = Peer.of(ByteArray(16) { 2 }, "Boris")
    private val low = CallId("00".repeat(16))
    private val high = CallId("ff".repeat(16))

    private fun m() = CallStateMachine()

    @Test fun outgoingAnsweredThenHungUpWritesLineWithDuration() {
        val m = m()
        assertEquals(listOf(SendOffer(low, anna, false)), m.on(Dial(low, anna, 0)))
        m.on(OfferDelivered(low, 500))
        assertTrue((m.state as CallState.Outgoing).ringing)
        assertEquals(listOf(ApplyAnswer("ans")), m.on(RemoteAnswer(low, "ans", 3_000)))
        m.on(IceConnected(4_000))
        assertTrue(m.state is CallState.Active)
        val fx = m.on(HangUp(196_000))
        assertTrue(SendHangup(low, anna, HangupReason.NORMAL) in fx)
        assertTrue(Line(anna, CallOutcome.OUTGOING, 192, 196_000) in fx)
        assertTrue(Release in fx)
        assertEquals(CallState.Idle, m.state)
    }

    @Test fun noAnswerAfter45s() {
        val m = m(); m.on(Dial(low, anna, 0))
        assertTrue(m.on(Tick(44_000)).isEmpty())
        val fx = m.on(Tick(45_000))
        assertTrue(SendHangup(low, anna, HangupReason.TIMEOUT) in fx)
        assertTrue(Line(anna, CallOutcome.NO_ANSWER, 0, 45_000) in fx)
        assertTrue(Ended(CallOutcome.NO_ANSWER) in fx)
    }

    @Test fun incomingAcceptedConnectsAndRemoteHangupEnds() {
        val m = m()
        assertTrue(m.on(RemoteOffer(high, boris, "offer", false, 1_000, false, 1_500)).isEmpty())
        assertTrue(m.state is CallState.Incoming)
        assertEquals(listOf(SendAnswer(high, boris, "offer")), m.on(Accept(5_000)))
        m.on(IceConnected(6_000))
        val fx = m.on(RemoteHangup(high, HangupReason.NORMAL, 66_000))
        assertTrue(Line(boris, CallOutcome.INCOMING, 60, 66_000) in fx)
        assertEquals(CallState.Idle, m.state)
    }

    @Test fun declineSendsDeclined() {
        val m = m(); m.on(RemoteOffer(high, boris, "o", false, 0, false, 0))
        val fx = m.on(Decline(2_000))
        assertTrue(SendHangup(high, boris, HangupReason.DECLINED) in fx)
        assertTrue(Line(boris, CallOutcome.DECLINED, 0, 2_000) in fx)
    }

    @Test fun callerSeesDeclinedAndBusy() {
        val m = m(); m.on(Dial(low, anna, 0))
        assertTrue(Line(anna, CallOutcome.DECLINED, 0, 9_000) in m.on(RemoteHangup(low, HangupReason.DECLINED, 9_000)))
        val m2 = m(); m2.on(Dial(low, anna, 0))
        assertTrue(Ended(CallOutcome.BUSY) in m2.on(RemoteHangup(low, HangupReason.BUSY, 1_000)))
    }

    @Test fun busyWhenInCallOrSystemBusy() {
        val m = m(); m.on(Dial(low, anna, 0))
        val fx = m.on(RemoteOffer(high, boris, "o", false, 1_000, false, 1_000))
        assertTrue(SendHangup(high, boris, HangupReason.BUSY) in fx)
        assertTrue(Missed(boris, 1_000) in fx)
        assertTrue(m.state is CallState.Outgoing)
        val m2 = m()
        assertTrue(SendHangup(high, boris, HangupReason.BUSY) in m2.on(RemoteOffer(high, boris, "o", false, 0, true, 0)))
        assertEquals(CallState.Idle, m2.state)
    }

    @Test fun staleOrHungUpOfferIsMissedWithoutRinging() {
        val m = m()
        val fx = m.on(RemoteOffer(high, boris, "o", false, 0, false, 45_000))
        assertEquals(listOf(Line(boris, CallOutcome.MISSED, 0, 45_000), Missed(boris, 45_000)), fx)
        assertEquals(CallState.Idle, m.state)
        val m2 = m()
        m2.on(RemoteHangup(high, HangupReason.NORMAL, 100)) // hangup overtook the offer
        assertTrue(Missed(boris, 200) in m2.on(RemoteOffer(high, boris, "o", false, 50, false, 200)))
        assertEquals(CallState.Idle, m2.state)
    }

    @Test fun glareSmallerCallIdWins() {
        // we dialled with the larger id; their smaller id wins: our call is dropped silently and theirs answered
        val m = m(); m.on(Dial(high, anna, 0))
        val fx = m.on(RemoteOffer(low, anna, "o", false, 100, false, 100))
        assertEquals(listOf(SendAnswer(low, anna, "o")), fx)
        assertEquals(low, (m.state as CallState.Connecting).id)
        // we dialled with the smaller id: their offer is ignored (they will answer ours)
        val m2 = m(); m2.on(Dial(low, anna, 0))
        assertTrue(m2.on(RemoteOffer(high, anna, "o", false, 100, false, 100)).isEmpty())
        assertEquals(low, (m2.state as CallState.Outgoing).id)
    }

    @Test fun glareLoserOfferArrivingAfterWinnerConnectedIsDroppedSilently() {
        // our smaller-id call was already answered (mailbox reordered their offer behind their answer)
        val m = m(); m.on(Dial(low, anna, 0)); m.on(RemoteAnswer(low, "a", 100))
        assertTrue(m.on(RemoteOffer(high, anna, "o", false, 50, false, 200)).isEmpty())
        assertEquals(low, (m.state as CallState.Connecting).id)
        m.on(IceConnected(300))
        assertTrue(m.on(RemoteOffer(high, anna, "o", false, 50, false, 400)).isEmpty())
        assertTrue(m.state is CallState.Active)
    }

    @Test fun iceNotConnectedIn15sFails() {
        val m = m(); m.on(Dial(low, anna, 0)); m.on(RemoteAnswer(low, "a", 1_000))
        assertTrue(m.on(Tick(15_999)).isEmpty())
        val fx = m.on(Tick(16_000))
        assertTrue(SendHangup(low, anna, HangupReason.FAILED) in fx)
        assertTrue(Ended(CallOutcome.FAILED) in fx)
    }

    @Test fun oneIceRestartThenFailAfter20s() {
        val m = m(); m.on(Dial(low, anna, 0)); m.on(RemoteAnswer(low, "a", 1_000)); m.on(IceConnected(2_000))
        assertEquals(listOf(SendOffer(low, anna, true)), m.on(IceLost(10_000)))
        assertEquals(listOf(ApplyAnswer("a2")), m.on(RemoteAnswer(low, "a2", 11_000)))
        m.on(IceConnected(12_000))
        assertNull((m.state as CallState.Active).lostSinceMs)
        assertTrue(m.on(IceLost(20_000)).isEmpty()) // second loss: no second restart
        val fx = m.on(Tick(40_000))
        assertTrue(SendHangup(low, anna, HangupReason.FAILED) in fx)
        assertTrue(Line(anna, CallOutcome.OUTGOING, 38, 40_000) in fx)
    }

    @Test fun calleeAnswersRestartOffer() {
        val m = m(); m.on(RemoteOffer(high, boris, "o", false, 0, false, 0)); m.on(Accept(1_000)); m.on(IceConnected(2_000))
        assertEquals(listOf(SendAnswer(high, boris, "o2")), m.on(RemoteOffer(high, boris, "o2", true, 9_000, false, 9_000)))
        assertTrue(m.state is CallState.Active)
    }

    @Test fun duplicateOfferForCurrentCallIsIgnored() {
        val m = m(); m.on(RemoteOffer(high, boris, "o", false, 0, false, 0))
        assertTrue(m.on(RemoteOffer(high, boris, "o", false, 0, false, 500)).isEmpty())
        assertTrue(m.state is CallState.Incoming)
    }

    @Test fun incomingUnansweredRingTimesOutAsMissed() {
        val m = m(); m.on(RemoteOffer(high, boris, "o", false, 0, false, 0))
        val fx = m.on(Tick(45_000))
        assertTrue(Missed(boris, 45_000) in fx)
        assertEquals(CallState.Idle, m.state)
    }
}
