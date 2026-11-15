package app.ottermsg.core.data.calls

/** 16-byte call id as lowercase hex; hex order == unsigned bytewise order (glare rule, D78). */
@JvmInline
value class CallId(val hex: String) : Comparable<CallId> {
    override fun compareTo(other: CallId): Int = hex.compareTo(other.hex)
    fun bytes(): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    companion object {
        fun of(b: ByteArray) = CallId(b.joinToString("") { "%02x".format(it) })
        fun random(): CallId = of(ByteArray(16).also(java.security.SecureRandom()::nextBytes))
    }
}

data class Peer(val userId: List<Byte>, val name: String) {
    val id: ByteArray get() = userId.toByteArray()
    companion object { fun of(id: ByteArray, name: String) = Peer(id.toList(), name) }
}

enum class HangupReason { NORMAL, BUSY, DECLINED, TIMEOUT, FAILED }

/** Stored in messages.call_outcome (kind CALL); codes are persisted — never renumber. */
enum class CallOutcome(val code: Int) {
    OUTGOING(1), INCOMING(2), MISSED(3), DECLINED(4), NO_ANSWER(5), BUSY(6), FAILED(7);
    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
}

object CallTimers { // D81
    const val RING_MS = 45_000L
    const val ICE_CONNECT_MS = 15_000L
    const val ICE_RESTART_MS = 20_000L
    const val GATHER_MS = 2_000L
    /** Login allows ±5 min (D52); an offer dated further ahead is not trusted to be fresh (D108). */
    const val MAX_SKEW_MS = 5 * 60_000L
}

sealed interface CallState {
    data object Idle : CallState
    data class Outgoing(val id: CallId, val peer: Peer, val ringing: Boolean, val sinceMs: Long) : CallState
    data class Incoming(val id: CallId, val peer: Peer, val offerSdp: String, val sinceMs: Long) : CallState
    data class Connecting(val id: CallId, val peer: Peer, val outgoing: Boolean, val sinceMs: Long) : CallState
    data class Active(val id: CallId, val peer: Peer, val outgoing: Boolean, val startMs: Long,
                      val lostSinceMs: Long? = null, val restarted: Boolean = false) : CallState
}

sealed interface CallEvent {
    val nowMs: Long
    data class Dial(val id: CallId, val peer: Peer, override val nowMs: Long) : CallEvent
    data class OfferDelivered(val id: CallId, override val nowMs: Long) : CallEvent
    data class RemoteOffer(val id: CallId, val peer: Peer, val sdp: String, val restart: Boolean, val createdMs: Long,
                           val systemBusy: Boolean, override val nowMs: Long) : CallEvent
    data class RemoteAnswer(val id: CallId, val sdp: String, override val nowMs: Long) : CallEvent
    data class RemoteHangup(val id: CallId, val reason: HangupReason, override val nowMs: Long) : CallEvent
    data class Accept(override val nowMs: Long) : CallEvent
    data class Decline(override val nowMs: Long) : CallEvent
    data class HangUp(override val nowMs: Long) : CallEvent
    data class IceConnected(override val nowMs: Long) : CallEvent
    data class IceLost(override val nowMs: Long) : CallEvent
    data class Tick(override val nowMs: Long) : CallEvent
}

sealed interface CallEffect {
    data class SendOffer(val id: CallId, val peer: Peer, val restart: Boolean) : CallEffect
    data class SendAnswer(val id: CallId, val peer: Peer, val offerSdp: String) : CallEffect
    data class ApplyAnswer(val sdp: String) : CallEffect
    data class SendHangup(val id: CallId, val peer: Peer, val reason: HangupReason) : CallEffect
    data class Line(val peer: Peer, val outcome: CallOutcome, val durationS: Long, val atMs: Long) : CallEffect
    data class Missed(val peer: Peer, val atMs: Long) : CallEffect
    data class Ended(val outcome: CallOutcome) : CallEffect
    data object Release : CallEffect
}
