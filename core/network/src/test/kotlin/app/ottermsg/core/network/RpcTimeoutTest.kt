package app.ottermsg.core.network

import app.ottermsg.proto.v1.Empty
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcTimeoutTest {
    /** Accepts frames but never answers. */
    private class SilentConnection : Connection {
        override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override fun send(bytes: ByteArray) = true
        override fun close() { incoming.close() }
    }

    @Test fun unansweredCallTimesOut() = runTest {
        val rpc = RpcClient(SilentConnection(), backgroundScope, timeoutMs = 5_000)
        val e = runCatching { rpc.call("ping", Empty.getDefaultInstance()) }.exceptionOrNull()
        assertTrue("got $e", e is RpcTimeoutException)
    }

    @Test fun closeFailsPendingCalls() = runTest {
        val conn = SilentConnection()
        val rpc = RpcClient(conn, backgroundScope)
        val call = async { runCatching { rpc.call("ping", Empty.getDefaultInstance()) }.exceptionOrNull() }
        yield()
        conn.close()
        val e = call.await()
        assertTrue("got $e", e is java.io.IOException && e !is RpcTimeoutException)
    }
}
