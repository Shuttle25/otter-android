package app.ottermsg.core.data.calls

import app.ottermsg.core.data.calls.CallEffect.*
import app.ottermsg.core.data.calls.CallEvent.*
import app.ottermsg.core.data.calls.CallTimers.ICE_CONNECT_MS
import app.ottermsg.core.data.calls.CallTimers.ICE_RESTART_MS
import app.ottermsg.core.data.calls.CallTimers.RING_MS

/** One call at a time (D78/D81). Not thread-safe: CallManager serializes events. */
class CallStateMachine {
    var state: CallState = CallState.Idle
        private set
    private val ended = LinkedHashMap<CallId, Long>() // call ids already hung up (a hangup may overtake its offer)

    fun on(ev: CallEvent): List<CallEffect> {
        ended.entries.removeIf { ev.nowMs - it.value > 10 * 60_000 }
        return when (ev) {
            is Dial -> dial(ev)
            is OfferDelivered -> { (state as? CallState.Outgoing)?.takeIf { it.id == ev.id }?.let { state = it.copy(ringing = true) }; emptyList() }
            is RemoteOffer -> remoteOffer(ev)
            is RemoteAnswer -> remoteAnswer(ev)
            is RemoteHangup -> remoteHangup(ev)
            is Accept -> (state as? CallState.Incoming)?.let {
                state = CallState.Connecting(it.id, it.peer, outgoing = false, sinceMs = ev.nowMs)
                listOf(SendAnswer(it.id, it.peer, it.offerSdp))
            } ?: emptyList()
            is Decline -> (state as? CallState.Incoming)?.let {
                end(it.id, listOf(SendHangup(it.id, it.peer, HangupReason.DECLINED), Line(it.peer, CallOutcome.DECLINED, 0, ev.nowMs)), ev.nowMs)
            } ?: emptyList()
            is HangUp -> hangUp(ev)
            is IceConnected -> {
                when (val s = state) {
                    is CallState.Connecting -> state = CallState.Active(s.id, s.peer, s.outgoing, startMs = ev.nowMs)
                    is CallState.Active -> state = s.copy(lostSinceMs = null)
                    else -> {}
                }
                emptyList()
            }
            is IceLost -> iceLost(ev)
            is Tick -> tick(ev)
        }
    }

    private fun dial(ev: Dial): List<CallEffect> {
        if (state != CallState.Idle) return emptyList()
        state = CallState.Outgoing(ev.id, ev.peer, ringing = false, sinceMs = ev.nowMs)
        return listOf(SendOffer(ev.id, ev.peer, restart = false))
    }

    private fun remoteOffer(ev: RemoteOffer): List<CallEffect> {
        val s = state
        if (ev.restart) {
            val same = (s as? CallState.Active)?.id == ev.id || (s as? CallState.Connecting)?.id == ev.id
            return if (same) listOf(SendAnswer(ev.id, ev.peer, ev.sdp)) else emptyList()
        }
        if (idOf(s) == ev.id) return emptyList() // the same offer delivered twice
        if (ev.id in ended || ev.nowMs - ev.createdMs >= RING_MS) {
            return listOf(Line(ev.peer, CallOutcome.MISSED, 0, ev.nowMs), Missed(ev.peer, ev.nowMs))
        }
        if (s is CallState.Outgoing && s.peer == ev.peer) { // glare (D78)
            if (ev.id < s.id) {
                state = CallState.Connecting(ev.id, ev.peer, outgoing = false, sinceMs = ev.nowMs)
                return listOf(SendAnswer(ev.id, ev.peer, ev.sdp))
            }
            return emptyList()
        }
        if (glareLoser(s, ev)) return emptyList() // our winning call already answered; their losing offer came late
        if (s != CallState.Idle || ev.systemBusy) {
            ended[ev.id] = ev.nowMs
            return listOf(SendHangup(ev.id, ev.peer, HangupReason.BUSY), Line(ev.peer, CallOutcome.MISSED, 0, ev.nowMs), Missed(ev.peer, ev.nowMs))
        }
        state = CallState.Incoming(ev.id, ev.peer, ev.sdp, sinceMs = ev.nowMs)
        return emptyList()
    }

    private fun remoteAnswer(ev: RemoteAnswer): List<CallEffect> = when (val s = state) {
        is CallState.Outgoing -> if (s.id == ev.id) {
            state = CallState.Connecting(s.id, s.peer, outgoing = true, sinceMs = ev.nowMs)
            listOf(ApplyAnswer(ev.sdp))
        } else emptyList()
        is CallState.Active -> if (s.id == ev.id && s.outgoing) listOf(ApplyAnswer(ev.sdp)) else emptyList()
        else -> emptyList()
    }

    private fun remoteHangup(ev: RemoteHangup): List<CallEffect> {
        ended[ev.id] = ev.nowMs
        return when (val s = state) {
            is CallState.Outgoing -> if (s.id != ev.id) emptyList() else {
                val o = when (ev.reason) {
                    HangupReason.BUSY -> CallOutcome.BUSY
                    HangupReason.DECLINED -> CallOutcome.DECLINED
                    HangupReason.FAILED -> CallOutcome.FAILED
                    else -> CallOutcome.NO_ANSWER
                }
                end(s.id, listOf(Line(s.peer, o, 0, ev.nowMs), Ended(o)), ev.nowMs)
            }
            is CallState.Incoming -> if (s.id != ev.id) emptyList() else
                end(s.id, listOf(Line(s.peer, CallOutcome.MISSED, 0, ev.nowMs), Missed(s.peer, ev.nowMs)), ev.nowMs)
            is CallState.Connecting -> if (s.id != ev.id) emptyList() else {
                val o = if (ev.reason == HangupReason.FAILED) CallOutcome.FAILED else dir(s.outgoing)
                end(s.id, listOf(Line(s.peer, o, 0, ev.nowMs), Ended(o)), ev.nowMs)
            }
            is CallState.Active -> if (s.id != ev.id) emptyList() else
                end(s.id, listOf(Line(s.peer, dir(s.outgoing), secs(s.startMs, ev.nowMs), ev.nowMs), Ended(dir(s.outgoing))), ev.nowMs)
            CallState.Idle -> emptyList()
        }
    }

    private fun hangUp(ev: HangUp): List<CallEffect> = when (val s = state) {
        is CallState.Outgoing -> end(s.id, listOf(SendHangup(s.id, s.peer, HangupReason.NORMAL),
            Line(s.peer, CallOutcome.OUTGOING, 0, ev.nowMs), Ended(CallOutcome.OUTGOING)), ev.nowMs)
        is CallState.Incoming -> end(s.id, listOf(SendHangup(s.id, s.peer, HangupReason.DECLINED),
            Line(s.peer, CallOutcome.DECLINED, 0, ev.nowMs)), ev.nowMs)
        is CallState.Connecting -> end(s.id, listOf(SendHangup(s.id, s.peer, HangupReason.NORMAL),
            Line(s.peer, dir(s.outgoing), 0, ev.nowMs), Ended(dir(s.outgoing))), ev.nowMs)
        is CallState.Active -> end(s.id, listOf(SendHangup(s.id, s.peer, HangupReason.NORMAL),
            Line(s.peer, dir(s.outgoing), secs(s.startMs, ev.nowMs), ev.nowMs), Ended(dir(s.outgoing))), ev.nowMs)
        CallState.Idle -> emptyList()
    }

    private fun iceLost(ev: IceLost): List<CallEffect> {
        val s = state as? CallState.Active ?: return emptyList()
        if (s.lostSinceMs != null) return emptyList()
        if (s.restarted) { state = s.copy(lostSinceMs = ev.nowMs); return emptyList() }
        state = s.copy(lostSinceMs = ev.nowMs, restarted = true)
        // only the caller restarts (no restart glare); the callee waits for the restart offer
        return if (s.outgoing) listOf(SendOffer(s.id, s.peer, restart = true)) else emptyList()
    }

    private fun tick(ev: Tick): List<CallEffect> = when (val s = state) {
        is CallState.Outgoing -> if (ev.nowMs - s.sinceMs < RING_MS) emptyList() else
            end(s.id, listOf(SendHangup(s.id, s.peer, HangupReason.TIMEOUT), Line(s.peer, CallOutcome.NO_ANSWER, 0, ev.nowMs),
                Ended(CallOutcome.NO_ANSWER)), ev.nowMs)
        is CallState.Incoming -> if (ev.nowMs - s.sinceMs < RING_MS) emptyList() else
            end(s.id, listOf(Line(s.peer, CallOutcome.MISSED, 0, ev.nowMs), Missed(s.peer, ev.nowMs)), ev.nowMs)
        is CallState.Connecting -> if (ev.nowMs - s.sinceMs < ICE_CONNECT_MS) emptyList() else
            end(s.id, listOf(SendHangup(s.id, s.peer, HangupReason.FAILED), Line(s.peer, CallOutcome.FAILED, 0, ev.nowMs),
                Ended(CallOutcome.FAILED)), ev.nowMs)
        is CallState.Active -> {
            val lost = s.lostSinceMs
            if (lost == null || ev.nowMs - lost < ICE_RESTART_MS) emptyList() else
                end(s.id, listOf(SendHangup(s.id, s.peer, HangupReason.FAILED),
                    Line(s.peer, dir(s.outgoing), secs(s.startMs, ev.nowMs), ev.nowMs), Ended(CallOutcome.FAILED)), ev.nowMs)
        }
        CallState.Idle -> emptyList()
    }

    private fun end(id: CallId, fx: List<CallEffect>, nowMs: Long): List<CallEffect> {
        ended[id] = nowMs
        state = CallState.Idle
        return fx + Release
    }

    private fun idOf(s: CallState): CallId? = when (s) {
        is CallState.Outgoing -> s.id; is CallState.Incoming -> s.id; is CallState.Connecting -> s.id; is CallState.Active -> s.id
        CallState.Idle -> null
    }

    private fun glareLoser(s: CallState, ev: RemoteOffer): Boolean = when (s) {
        is CallState.Connecting -> s.outgoing && s.peer == ev.peer && s.id < ev.id
        is CallState.Active -> s.outgoing && s.peer == ev.peer && s.id < ev.id
        else -> false
    }

    private fun dir(outgoing: Boolean) = if (outgoing) CallOutcome.OUTGOING else CallOutcome.INCOMING
    private fun secs(from: Long, to: Long) = ((to - from) / 1000).coerceAtLeast(0)
}
