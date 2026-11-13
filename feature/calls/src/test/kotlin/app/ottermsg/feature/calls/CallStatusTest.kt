package app.ottermsg.feature.calls

import androidx.core.telecom.CallEndpointCompat.Companion.TYPE_BLUETOOTH
import androidx.core.telecom.CallEndpointCompat.Companion.TYPE_EARPIECE
import androidx.core.telecom.CallEndpointCompat.Companion.TYPE_SPEAKER
import androidx.core.telecom.CallEndpointCompat.Companion.TYPE_WIRED_HEADSET
import app.ottermsg.core.data.calls.CallId
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStatusTest {
    private val id = CallId("00".repeat(16))
    private val peer = Peer.of(ByteArray(16) { 1 }, "Мама")

    @Test fun statesMapToTheScreenText() {
        assertEquals(CallStatus.None, callStatus(CallState.Idle, 0))
        assertEquals(CallStatus.Dialing, callStatus(CallState.Outgoing(id, peer, ringing = false, sinceMs = 0), 0))
        assertEquals(CallStatus.Ringing, callStatus(CallState.Outgoing(id, peer, ringing = true, sinceMs = 0), 0))
        assertEquals(CallStatus.Incoming, callStatus(CallState.Incoming(id, peer, "sdp", 0), 0))
        assertEquals(CallStatus.Connecting, callStatus(CallState.Connecting(id, peer, outgoing = false, sinceMs = 0), 0))
        assertEquals(CallStatus.Timer(192), callStatus(CallState.Active(id, peer, true, startMs = 1_000), 193_999))
        assertEquals(CallStatus.Timer(0), callStatus(CallState.Active(id, peer, true, startMs = 5_000), 4_000)) // clock skew
        assertEquals(CallStatus.Reconnecting, callStatus(CallState.Active(id, peer, true, startMs = 0, lostSinceMs = 9), 10))
    }

    @Test fun timerIsMinutesAndSeconds() {
        assertEquals("0:05", timerText(5))
        assertEquals("3:12", timerText(192))
        assertEquals("61:05", timerText(3665))
    }

    @Test fun speakerTogglesToThePrivateRoute() {
        assertEquals(TYPE_SPEAKER, CallAudio.toggleTarget(TYPE_EARPIECE, listOf(TYPE_EARPIECE, TYPE_SPEAKER)))
        assertEquals(TYPE_EARPIECE, CallAudio.toggleTarget(TYPE_SPEAKER, listOf(TYPE_EARPIECE, TYPE_SPEAKER)))
        assertEquals(TYPE_BLUETOOTH, CallAudio.toggleTarget(TYPE_SPEAKER, listOf(TYPE_EARPIECE, TYPE_SPEAKER, TYPE_WIRED_HEADSET, TYPE_BLUETOOTH)))
        assertEquals(TYPE_WIRED_HEADSET, CallAudio.toggleTarget(TYPE_SPEAKER, listOf(TYPE_SPEAKER, TYPE_WIRED_HEADSET)))
        assertNull(CallAudio.toggleTarget(TYPE_EARPIECE, listOf(TYPE_EARPIECE)))
        assertEquals(TYPE_SPEAKER, CallAudio.toggleTarget(null, listOf(TYPE_SPEAKER)))
    }
}
