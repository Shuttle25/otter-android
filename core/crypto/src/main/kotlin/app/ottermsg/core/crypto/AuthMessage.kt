package app.ottermsg.core.crypto

import java.nio.ByteBuffer

/** The byte string a client signs to log in. Must match Go `keys.AuthMessage`. */
object AuthMessage {
    private val CONTEXT = "otter-auth-v1".toByteArray()

    fun build(nodeId: ByteArray, nonce: ByteArray, tsMs: Long): ByteArray =
        ByteBuffer.allocate(CONTEXT.size + nodeId.size + nonce.size + 8)
            .put(CONTEXT).put(nodeId).put(nonce).putLong(tsMs).array()
}
