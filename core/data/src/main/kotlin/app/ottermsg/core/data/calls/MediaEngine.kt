package app.ottermsg.core.data.calls

data class IceServerSpec(val urls: List<String>, val username: String, val password: String)
data class Candidate(val sdpMid: String, val mLineIndex: Int, val sdp: String)
enum class IcePhase { CONNECTED, LOST }
/** path: "relay-udp", "relay-tcp", "relay-tls" or "direct". */
data class PathInfo(val path: String, val rttMs: Long)

interface MediaListener {
    fun onIce(phase: IcePhase)
    fun onCandidate(c: Candidate) // only candidates found after the SDP was returned (trickle)
}

/** WebRTC behind an interface so CallManager stays testable on the JVM (D76). One instance per call. */
interface MediaEngine {
    suspend fun open(servers: List<IceServerSpec>, relayOnly: Boolean, listener: MediaListener)
    suspend fun offer(restart: Boolean): String
    suspend fun answer(remoteOffer: String): String
    suspend fun applyAnswer(sdp: String)
    /** Only after the remote description is set (CallManager buffers until then). */
    fun addCandidates(c: List<Candidate>)
    fun setMuted(muted: Boolean)
    suspend fun path(): PathInfo?
    fun close()
    fun interface Factory { fun create(): MediaEngine }
}
