package app.ottermsg.core.data.calls

import app.ottermsg.proto.v1.CallMessage
import app.ottermsg.proto.v1.ReceiptMessage

/** Receives call content from MailboxReceiver (implemented by CallManager). Called outside any DB transaction. */
interface CallInbox {
    /** [createdMs] is the record's creation time (stale offers are missed calls, D78). */
    suspend fun onCallMessage(from: ByteArray, name: String, msg: CallMessage, createdMs: Long)
    /** True when [r] is about a call id (DELIVERED for an offer), not a chat message. */
    fun isCallReceipt(r: ReceiptMessage): Boolean
    suspend fun onCallReceipt(from: ByteArray, r: ReceiptMessage)

    companion object {
        val NONE = object : CallInbox {
            override suspend fun onCallMessage(from: ByteArray, name: String, msg: CallMessage, createdMs: Long) {}
            override fun isCallReceipt(r: ReceiptMessage) = false
            override suspend fun onCallReceipt(from: ByteArray, r: ReceiptMessage) {}
        }
    }
}
