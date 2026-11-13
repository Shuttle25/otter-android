package app.ottermsg.feature.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusSdpTest {
    @Test fun addsDtxAndBitrateToOpusFmtp() {
        val sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;useinbandfec=1\r\n"
        val out = tuneOpus(sdp)
        assertTrue(out.contains("a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=32000;stereo=0\r\n"))
        assertEquals(out, tuneOpus(out)) // idempotent
    }

    @Test fun otherCodecsAndSdpWithoutOpusAreUntouched() {
        val sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111 63\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10\r\n" +
            "a=rtpmap:63 red/48000/2\r\na=fmtp:63 111/111\r\n"
        assertTrue(tuneOpus(sdp).endsWith("a=fmtp:63 111/111\r\n"))
        val noOpus = "m=audio 9 UDP/TLS/RTP/SAVPF 0\r\na=rtpmap:0 PCMU/8000\r\n"
        assertEquals(noOpus, tuneOpus(noOpus))
    }
}
