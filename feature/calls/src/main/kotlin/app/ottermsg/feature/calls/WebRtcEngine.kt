package app.ottermsg.feature.calls

import android.content.Context
import app.ottermsg.core.data.calls.Candidate
import app.ottermsg.core.data.calls.CallTimers
import app.ottermsg.core.data.calls.IcePhase
import app.ottermsg.core.data.calls.IceServerSpec
import app.ottermsg.core.data.calls.MediaEngine
import app.ottermsg.core.data.calls.MediaListener
import app.ottermsg.core.data.calls.PathInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebRTC M150 audio-only engine (D76/D77): Opus mono with DTX, hardware AEC/NS when the device has them, unified plan,
 * max-bundle, ICE policy RELAY when «Звонки только через сервер» is on. One instance per call.
 */
class WebRtcEngine(private val context: Context) : MediaEngine {
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var source: AudioSource? = null
    @Volatile private var pc: PeerConnection? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var listener: MediaListener? = null
    @Volatile private var trickle = false
    @Volatile private var gatheringDone: CompletableDeferred<Unit>? = null

    override suspend fun open(servers: List<IceServerSpec>, relayOnly: Boolean, listener: MediaListener) = withContext(Dispatchers.Default) {
        init(context)
        this@WebRtcEngine.listener = listener
        val a = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setUseStereoInput(false).setUseStereoOutput(false)
            .createAudioDeviceModule().also { adm = it }
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(a).createPeerConnectionFactory().also { factory = it }
        val ice = servers.map {
            PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.password)
                // the node's TURN TLS certificate is not checked: media is DTLS-SRTP end to end and the
                // fingerprints travel only inside the Signal-encrypted offer/answer (D77)
                .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK)
                .createIceServer()
        }
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val p = f.createPeerConnection(cfg, observer) ?: error("createPeerConnection failed")
        pc = p
        val s = f.createAudioSource(MediaConstraints()).also { source = it }
        val t = f.createAudioTrack("a0", s).also { track = it }
        p.addTrack(t, listOf("s0"))
        Unit
    }

    override suspend fun offer(restart: Boolean): String {
        val p = checkNotNull(pc)
        if (restart) p.restartIce()
        val sdp = suspendSdp { p.createOffer(it, MediaConstraints()) }
        return setLocalAndGather(p, SessionDescription(sdp.type, tuneOpus(sdp.description)))
    }

    override suspend fun answer(remoteOffer: String): String {
        val p = checkNotNull(pc)
        suspendSet { p.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, remoteOffer)) }
        val sdp = suspendSdp { p.createAnswer(it, MediaConstraints()) }
        return setLocalAndGather(p, SessionDescription(sdp.type, tuneOpus(sdp.description)))
    }

    override suspend fun applyAnswer(sdp: String) {
        val p = checkNotNull(pc)
        suspendSet { p.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
    }

    override fun addCandidates(c: List<Candidate>) { c.forEach { pc?.addIceCandidate(IceCandidate(it.sdpMid, it.mLineIndex, it.sdp)) } }
    override fun setMuted(muted: Boolean) { track?.setEnabled(!muted) }

    override suspend fun path(): PathInfo? = suspendCancellableCoroutine { cont ->
        val p = pc ?: return@suspendCancellableCoroutine cont.resume(null)
        p.getStats { report ->
            val stats = report.statsMap
            val pair = stats.values.firstOrNull {
                it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded"
            }
            val local = pair?.let { stats[it.members["localCandidateId"] as? String] }
            val path = when {
                local == null -> null
                local.members["candidateType"] == "relay" -> "relay-" + ((local.members["relayProtocol"] as? String) ?: "udp")
                else -> "direct"
            }
            val rtt = ((pair?.members?.get("currentRoundTripTime") as? Double) ?: 0.0) * 1000
            if (cont.isActive) cont.resume(path?.let { PathInfo(it, rtt.toLong()) })
        }
    }

    override fun close() {
        listener = null; trickle = false
        runCatching { pc?.dispose() }; pc = null // also disposes the sender's track
        track = null
        runCatching { source?.dispose() }; source = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { adm?.release() }; adm = null
    }

    /** Sets the local description and waits ≤ 2 s for candidates (D81); later ones are trickled. */
    private suspend fun setLocalAndGather(p: PeerConnection, sdp: SessionDescription): String {
        trickle = false
        gatheringDone = CompletableDeferred()
        suspendSet { p.setLocalDescription(it, sdp) }
        withTimeoutOrNull(CallTimers.GATHER_MS) { gatheringDone?.await() }
        trickle = true
        return p.localDescription.description // includes the candidates gathered so far
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) { if (trickle) listener?.onCandidate(Candidate(c.sdpMid, c.sdpMLineIndex, c.sdp)) }
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gatheringDone?.complete(Unit)
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> listener?.onIce(IcePhase.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED -> listener?.onIce(IcePhase.LOST)
                else -> {}
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    private suspend fun suspendSdp(block: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d) }
            override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(e)) }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        })
    }

    private suspend fun suspendSet(block: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(e)) }
        })
    }

    companion object {
        @Volatile private var initialized = false
        @Synchronized fun init(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
            initialized = true
        }
    }
}
