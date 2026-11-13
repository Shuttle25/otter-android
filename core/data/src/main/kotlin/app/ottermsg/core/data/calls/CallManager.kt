package app.ottermsg.core.data.calls

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.network.FailureClass
import app.ottermsg.core.network.NetLog
import app.ottermsg.core.network.NetLogEntry
import app.ottermsg.proto.v1.CallAnswer
import app.ottermsg.proto.v1.CallHangup
import app.ottermsg.proto.v1.CallMessage
import app.ottermsg.proto.v1.CallOffer
import app.ottermsg.proto.v1.IceCandidate
import app.ottermsg.proto.v1.IceCandidates
import app.ottermsg.proto.v1.ReceiptMessage
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** One-off messages for the UI (toasts / dialogs). */
sealed interface CallNotice {
    /** The connected node has no TURN relay (TURN off, or a node from before 0.8.0). */
    data object NotAvailable : CallNotice
    /** No node connection within [CallManager.CONNECT_WAIT_MS], or the credentials request failed. */
    data object Offline : CallNotice
}

/**
 * Runs [CallStateMachine] against real signaling ([CallTransport]), TURN credentials ([TurnCache]) and a [MediaEngine].
 * All state changes happen on one event loop; the public methods only post events. One call at a time (D78).
 */
@Singleton
class CallManager @Inject constructor(
    private val transport: CallTransport,
    private val lines: CallLines,
    private val engines: MediaEngine.Factory,
    private val turn: TurnCache,
    private val link: CallLink,
    private val settings: Settings,
    private val alerts: CallAlerts,
    private val netLog: NetLog,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) : CallInbox, CallStarter {
    private sealed interface Op {
        data class Ev(val ev: CallEvent) : Op
        data class RemoteCandidates(val id: CallId, val c: List<Candidate>) : Op
    }

    /** Thrown by [open]: the call cannot start here; ends it without the usual hangup/line (caller side). */
    private class Abort(val notice: CallNotice) : Exception()

    private val machine = CallStateMachine()
    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    override val state: StateFlow<CallState> = _state.asStateFlow()
    private val _ended = MutableSharedFlow<CallOutcome>(extraBufferCapacity = 4)
    /** Outcome of every call that had a call screen (not emitted for missed / declined incoming calls). */
    val ended: SharedFlow<CallOutcome> = _ended.asSharedFlow()
    private val _notices = MutableSharedFlow<CallNotice>(extraBufferCapacity = 4)
    override val notices: SharedFlow<CallNotice> = _notices.asSharedFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Set by the Android layer: another call (GSM or another VoIP app) is in progress. */
    @Volatile var systemBusy: () -> Boolean = { false }

    // Event-loop state; the @Volatile ones are also read from other threads.
    @Volatile private var engine: MediaEngine? = null
    @Volatile private var trickleTo: Pair<CallId, Peer>? = null
    /** Path seen shortly after ICE connected: when the peer hangs up first, the live stats no longer have a pair. */
    @Volatile private var lastPath: PathInfo? = null
    private var remoteReady = false
    private var remotePending: Pair<CallId, MutableList<Candidate>>? = null
    private val pending = mutableListOf<Candidate>()
    private var flush: Job? = null
    private var ticker: Job? = null
    private var lastLineChat = -1L
    private var leased = false
    /** Our recent offer ids (hex): their DELIVERED receipts are call receipts (checked synchronously). */
    private val sentOffers = LinkedHashSet<String>()

    init {
        scope.launch {
            for (op in ops) {
                try {
                    when (op) {
                        is Op.Ev -> process(op.ev)
                        is Op.RemoteCandidates -> remoteCandidates(op.id, op.c)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log(Level.WARNING, "call event failed", e)
                }
            }
        }
    }

    override fun dial(peer: Peer) = post(CallEvent.Dial(CallId.random(), peer, clock.now()))
    fun accept() = post(CallEvent.Accept(clock.now()))
    fun decline() = post(CallEvent.Decline(clock.now()))
    fun hangUp() = post(CallEvent.HangUp(clock.now()))
    fun setMuted(muted: Boolean) { _muted.value = muted; engine?.setMuted(muted) }
    /** Media path of the current call (relay-udp / relay-tcp / relay-tls / direct); null before ICE connects. */
    suspend fun path(): PathInfo? = engine?.path()
    /** May [peer]'s phone be woken by push? True when unknown (offline, old node). */
    override suspend fun peerHasPush(peer: ByteArray): Boolean =
        runCatching { link.api(0)?.pushStatus(peer) ?: true }.getOrDefault(true)

    private fun post(ev: CallEvent) { ops.trySend(Op.Ev(ev)) }

    // ---- CallInbox (MailboxReceiver; must never throw) ----

    override suspend fun onCallMessage(from: ByteArray, name: String, msg: CallMessage, createdMs: Long) {
        try {
            val id = CallId.of(msg.callId.toByteArray())
            val peer = Peer.of(from, name)
            val now = clock.now()
            when (msg.kindCase) {
                CallMessage.KindCase.OFFER -> {
                    if (!msg.offer.restart) runCatching { transport.delivered(from, msg.callId.toByteArray()) }
                    val busy = runCatching { systemBusy() }.getOrDefault(false)
                    post(CallEvent.RemoteOffer(id, peer, msg.offer.sdp, msg.offer.restart, createdMs, busy, now))
                }
                CallMessage.KindCase.ANSWER -> post(CallEvent.RemoteAnswer(id, msg.answer.sdp, now))
                CallMessage.KindCase.CANDIDATES -> ops.trySend(Op.RemoteCandidates(id,
                    msg.candidates.candidatesList.map { Candidate(it.sdpMid, it.sdpMlineIndex, it.candidate) }))
                CallMessage.KindCase.HANGUP -> post(CallEvent.RemoteHangup(id, reasonOf(msg.hangup.reason), now))
                else -> {}
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "call message dropped", e)
        }
    }

    override fun isCallReceipt(r: ReceiptMessage): Boolean {
        if (r.type != ReceiptMessage.Type.DELIVERED) return false
        return synchronized(sentOffers) { r.messageIdsList.any { CallId.of(it.toByteArray()).hex in sentOffers } }
    }

    override suspend fun onCallReceipt(from: ByteArray, r: ReceiptMessage) {
        r.messageIdsList.forEach { post(CallEvent.OfferDelivered(CallId.of(it.toByteArray()), clock.now())) }
    }

    // ---- event loop ----

    private suspend fun process(ev: CallEvent) {
        val before = machine.state
        val fx = machine.on(ev)
        _state.value = machine.state
        if (before == CallState.Idle && machine.state != CallState.Idle) onBusy()
        for (e in fx) {
            try {
                run(e)
            } catch (c: CancellationException) {
                throw c
            } catch (a: Abort) {
                abort(a.notice)
                return
            } catch (x: Exception) {
                log.log(Level.WARNING, "call effect $e failed", x)
                fail()
            }
        }
        val now = machine.state
        if (now is CallState.Incoming && before !is CallState.Incoming) alerts.incoming(now)
        if (now == CallState.Idle && (leased || engine != null)) release() // safety net: Release is always emitted today
    }

    private suspend fun run(e: CallEffect) {
        when (e) {
            is CallEffect.SendOffer -> {
                val eng = engineFor(e.id, e.peer)
                if (!e.restart) synchronized(sentOffers) {
                    sentOffers += e.id.hex
                    while (sentOffers.size > 16) sentOffers.remove(sentOffers.first())
                }
                val sdp = eng.offer(e.restart)
                transport.send(e.peer.id, msg(e.id).setOffer(CallOffer.newBuilder().setSdp(sdp).setRestart(e.restart)).build())
            }
            is CallEffect.SendAnswer -> {
                val eng = engineFor(e.id, e.peer)
                val sdp = eng.answer(e.offerSdp)
                remoteReady = true
                applyPendingRemote(e.id)
                transport.send(e.peer.id, msg(e.id).setAnswer(CallAnswer.newBuilder().setSdp(sdp)).build())
            }
            is CallEffect.ApplyAnswer -> engine?.let { eng ->
                eng.applyAnswer(e.sdp)
                remoteReady = true
                trickleTo?.first?.let { applyPendingRemote(it) }
            }
            is CallEffect.SendHangup -> transport.send(e.peer.id,
                msg(e.id).setHangup(CallHangup.newBuilder().setReason(CallHangup.Reason.valueOf(e.reason.name))).build())
            is CallEffect.Line -> {
                val path = (try { engine?.path() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }) // before Release closes the engine
                    ?: lastPath
                lastLineChat = lines.write(e.peer, e.outcome, e.durationS, e.atMs)
                logCall(e, path)
            }
            is CallEffect.Missed -> alerts.missed(e.peer, lastLineChat)
            is CallEffect.Ended -> _ended.emit(e.outcome)
            CallEffect.Release -> release()
        }
    }

    /** One «Журнал подключений» line per call (D74 merge rule): FAILED → timeout, every other outcome → ok. */
    private suspend fun logCall(e: CallEffect.Line, p: PathInfo?) {
        val d = String.format(java.util.Locale.ROOT, "%d:%02d", e.durationS / 60, e.durationS % 60)
        runCatching {
            netLog.add(NetLogEntry(link.nodeLabel() ?: "-", "call:" + (p?.path ?: "none"),
                if (e.outcome == CallOutcome.FAILED) FailureClass.TIMEOUT else FailureClass.OK, "${e.outcome.name} $d", p?.rttMs ?: 0))
        }.onFailure { if (it is CancellationException) throw it; log.log(Level.WARNING, "call log line failed", it) }
    }

    /**
     * The engine of call [id]. On a lost glare our own outgoing call silently becomes their call (the machine emits
     * no hangup for ours): its engine is closed and a fresh one opened.
     */
    private suspend fun engineFor(id: CallId, peer: Peer): MediaEngine {
        engine?.let { eng -> if (trickleTo?.first == id) return eng; dropEngine() }
        return open(id, peer)
    }

    /** Opens the media engine with this node's relay; throws [Abort] when calls are not possible here. */
    private suspend fun open(id: CallId, peer: Peer): MediaEngine {
        val api = link.api(CONNECT_WAIT_MS) ?: throw Abort(CallNotice.Offline)
        val creds = try {
            turn.get(api)
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            log.log(Level.WARNING, "turn.credentials failed", x)
            throw Abort(CallNotice.Offline)
        } ?: throw Abort(CallNotice.NotAvailable)
        val eng = engines.create()
        trickleTo = id to peer
        engine = eng
        remoteReady = false
        eng.open(listOf(IceServerSpec(creds.urlsList, creds.username, creds.password)), settings.callsRelayOnly(), object : MediaListener {
            override fun onIce(phase: IcePhase) {
                post(if (phase == IcePhase.CONNECTED) CallEvent.IceConnected(clock.now()) else CallEvent.IceLost(clock.now()))
                if (phase == IcePhase.CONNECTED) scope.launch {
                    delay(PATH_SAMPLE_MS)
                    val eng = engine ?: return@launch
                    runCatching { eng.path() }.getOrNull()?.let { if (engine === eng) lastPath = it }
                }
            }
            override fun onCandidate(c: Candidate) { if (engine === eng) { synchronized(pending) { pending += c }; scheduleFlush() } }
        })
        if (_muted.value) eng.setMuted(true)
        return eng
    }

    /** Late remote candidates: applied once the remote description is set, kept until then (callee ringing). */
    private fun remoteCandidates(id: CallId, c: List<Candidate>) {
        val current = idOf(machine.state) ?: return
        if (current != id) return
        val eng = engine
        if (eng != null && remoteReady && trickleTo?.first == id) { eng.addCandidates(c); return }
        val p = remotePending?.takeIf { it.first == id } ?: (id to mutableListOf<Candidate>()).also { remotePending = it }
        p.second += c
    }

    private fun applyPendingRemote(id: CallId) {
        val p = remotePending ?: return
        remotePending = null
        if (p.first == id && p.second.isNotEmpty()) engine?.addCandidates(p.second)
    }

    private fun scheduleFlush() {
        synchronized(pending) {
            if (flush?.isActive == true) return
            flush = scope.launch {
                delay(TRICKLE_MS)
                val (id, peer) = trickleTo ?: return@launch
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (batch.isEmpty()) return@launch
                val c = IceCandidates.newBuilder().addAllCandidates(batch.map {
                    IceCandidate.newBuilder().setSdpMid(it.sdpMid).setSdpMlineIndex(it.mLineIndex).setCandidate(it.sdp).build() })
                runCatching { transport.send(peer.id, msg(id).setCandidates(c).build()) }
                    .onFailure { if (it is CancellationException) throw it; log.log(Level.WARNING, "candidates not sent", it) }
            }
        }
    }

    /** The node connection must stay up during a call (signaling, trickle, hangup). */
    private fun onBusy() {
        if (!leased) { link.acquire(LEASE); leased = true }
        if (ticker == null) ticker = scope.launch { while (isActive) { delay(1_000); post(CallEvent.Tick(clock.now())) } }
    }

    /** Ends a call that could not start: no hangup and no line for our own unsent offer, FAILED for an accepted one. */
    private suspend fun abort(notice: CallNotice) {
        _notices.emit(notice)
        val wasOutgoing = machine.state is CallState.Outgoing
        val fx = machine.on(CallEvent.HangUp(clock.now()))
        _state.value = machine.state
        for (e in fx) runCatching {
            when {
                e is CallEffect.Release -> release()
                wasOutgoing -> {}
                e is CallEffect.SendHangup -> run(e.copy(reason = HangupReason.FAILED))
                e is CallEffect.Line -> run(e.copy(outcome = CallOutcome.FAILED))
                e is CallEffect.Ended -> run(CallEffect.Ended(CallOutcome.FAILED))
                else -> run(e)
            }
        }.onFailure { if (it is CancellationException) throw it; log.log(Level.WARNING, "abort effect $e failed", it) }
        if (machine.state == CallState.Idle && (leased || engine != null)) release()
    }

    private fun dropEngine() {
        runCatching { engine?.close() }
        engine = null; trickleTo = null; remoteReady = false; lastPath = null
        flush?.cancel(); flush = null
        synchronized(pending) { pending.clear() }
    }

    private fun release() {
        dropEngine()
        remotePending = null
        _muted.value = false
        ticker?.cancel(); ticker = null
        if (leased) { link.release(LEASE); leased = false }
        runCatching { alerts.released() }
    }

    private fun fail() = post(CallEvent.HangUp(clock.now()))

    private fun msg(id: CallId) = CallMessage.newBuilder().setCallId(ByteString.copyFrom(id.bytes()))

    private fun idOf(s: CallState): CallId? = when (s) {
        is CallState.Outgoing -> s.id; is CallState.Incoming -> s.id; is CallState.Connecting -> s.id; is CallState.Active -> s.id
        CallState.Idle -> null
    }

    private fun reasonOf(r: CallHangup.Reason) = when (r) {
        CallHangup.Reason.BUSY -> HangupReason.BUSY
        CallHangup.Reason.DECLINED -> HangupReason.DECLINED
        CallHangup.Reason.TIMEOUT -> HangupReason.TIMEOUT
        CallHangup.Reason.FAILED -> HangupReason.FAILED
        else -> HangupReason.NORMAL
    }

    companion object {
        /** SyncEngine lease held while not Idle. */
        const val LEASE = "call"
        const val TRICKLE_MS = 300L
        const val CONNECT_WAIT_MS = 10_000L
        const val PATH_SAMPLE_MS = 2_000L // after ICE connects: the selected pair is settled by then
        private val log = Logger.getLogger("CallManager")
    }
}
