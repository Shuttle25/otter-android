package app.ottermsg.core.network

import app.ottermsg.proto.v1.Empty
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.Frame
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RpcClientTest {
    @Test fun callAndHello() = runTest {
        val t = FakeTransport { r -> if (r.method == "ping") ok() else err(Status.STATUS_INVALID, "nope") }
        val rpc = RpcClient(t.connect(cand("x")), backgroundScope)
        assertEquals(1, rpc.hello.await().protocolVersion)
        rpc.call("ping", Empty.getDefaultInstance())
        val e = runCatching { rpc.call("bogus", Empty.getDefaultInstance()) }.exceptionOrNull() as RpcException
        assertEquals(Status.STATUS_INVALID, e.status)
    }

    @Test fun eventsAndClose() = runTest {
        val t = FakeTransport { ok() }
        val conn = t.connect(cand("x")) as FakeTransport.FakeConnection
        val rpc = RpcClient(conn, backgroundScope)
        val ev = async { rpc.events.first() }
        rpc.hello.await()
        conn.push(Frame.newBuilder().setEvent(Event.newBuilder().setType("directory")).build())
        assertEquals("directory", ev.await().type)
        conn.drop()
        rpc.closed.await()
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { rpc.call("ping", Empty.getDefaultInstance()) } }
    }
}
