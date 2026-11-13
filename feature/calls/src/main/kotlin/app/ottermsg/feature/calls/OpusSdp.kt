package app.ottermsg.feature.calls

/** Opus mono, DTX, ≤ 32 kbit/s (D76): appended to the fmtp line of the opus payload type. Idempotent. */
fun tuneOpus(sdp: String): String {
    val pt = Regex("a=rtpmap:(\\d+) opus/48000/2").find(sdp)?.groupValues?.get(1) ?: return sdp
    return sdp.replace(Regex("a=fmtp:$pt ([^\\r\\n]*)")) { m ->
        val p = m.groupValues[1]
        if (p.contains("usedtx")) m.value else "a=fmtp:$pt $p;usedtx=1;maxaveragebitrate=32000;stereo=0"
    }
}
