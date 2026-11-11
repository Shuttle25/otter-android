package app.ottermsg.core.network

import kotlinx.coroutines.channels.ReceiveChannel

interface Transport {
    /** Opens a connection to [candidate] (pinned TLS when it has pins) or throws. */
    suspend fun connect(candidate: Candidate): Connection
}

interface Connection {
    /** Binary frames from the node; closed (possibly with an exception) when the connection ends. */
    val incoming: ReceiveChannel<ByteArray>
    fun send(bytes: ByteArray): Boolean
    fun close()
}
