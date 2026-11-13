package app.ottermsg.core.data.calls

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.messaging.Outbox
import app.ottermsg.proto.v1.CallMessage
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.ReceiptMessage
import com.google.protobuf.ByteString
import javax.inject.Inject
import javax.inject.Singleton

/** CallManager's signaling seam (CallSignaling in the app, a recorder in tests). */
interface CallTransport {
    suspend fun send(peer: ByteArray, msg: CallMessage)
    suspend fun delivered(peer: ByteArray, callId: ByteArray)
}

/** Call signaling over the ordinary Signal outbox (D78): no message row, so never kept for the 24 h resend. */
@Singleton
class CallSignaling @Inject constructor(private val outbox: Outbox, private val clock: Clock) : CallTransport {
    /** Urgent (push wakes the callee), 2-minute record expiry. */
    override suspend fun send(peer: ByteArray, msg: CallMessage) {
        val now = clock.now()
        outbox.enqueue(peer, Content.newBuilder().setCall(msg).setSentMs(now).build(), messageLocalId = null,
            urgent = true, expiresMs = now + EXPIRY_MS)
    }

    /** DELIVERED for an offer: the caller switches «Соединение…» to «Звонит…». */
    override suspend fun delivered(peer: ByteArray, callId: ByteArray) {
        val now = clock.now()
        val r = ReceiptMessage.newBuilder().setType(ReceiptMessage.Type.DELIVERED).addMessageIds(ByteString.copyFrom(callId))
        outbox.enqueue(peer, Content.newBuilder().setReceipt(r).setSentMs(now).build(), messageLocalId = null,
            urgent = false, expiresMs = now + EXPIRY_MS)
    }

    companion object { const val EXPIRY_MS = 120_000L }
}
