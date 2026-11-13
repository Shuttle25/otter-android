package app.ottermsg.core.data.calls

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** What a screen needs to start a call (implemented by [CallManager]; a seam for view-model tests). */
interface CallStarter {
    val state: StateFlow<CallState>
    val notices: SharedFlow<CallNotice>
    /** May [peer]'s phone be woken by push? True when unknown. */
    suspend fun peerHasPush(peer: ByteArray): Boolean
    fun dial(peer: Peer)

    companion object {
        val NONE = object : CallStarter {
            override val state: StateFlow<CallState> = MutableStateFlow(CallState.Idle)
            override val notices: SharedFlow<CallNotice> = MutableSharedFlow()
            override suspend fun peerHasPush(peer: ByteArray) = true
            override fun dial(peer: Peer) {}
        }
    }
}
