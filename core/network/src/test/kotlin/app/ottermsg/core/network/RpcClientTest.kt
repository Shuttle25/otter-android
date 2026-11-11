package app.ottermsg.core.network

import app.ottermsg.proto.v1.Empty
import app.ottermsg.proto.v1.Event
import app.ottermsg.proto.v1.Frame
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
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

    @Test fun slowEventHandlingDoesNotBlockResponses() = runTest {
        val t = FakeTransport { ok() }
        val conn = t.connect(cand("x")) as FakeTransport.FakeConnection
        val rpc = RpcClient(conn, backgroundScope)
        rpc.hello.await()
        val seen = mutableListOf<String>()
        val collector = backgroundScope.launch {
            rpc.events.collect { e ->
                if (seen.isEmpty()) delay(5_000) // a slow first handler (decrypting)
                seen += e.type
            }
        }
        repeat(1000) { conn.push(Frame.newBuilder().setEvent(Event.newBuilder().setType("e$it")).build()) }
        val t0 = currentTime
        rpc.call("ping", Empty.getDefaultInstance()) // answered while the collector is still busy
        assertEquals(t0, currentTime)
        advanceTimeBy(6_000)
        kotlinx.coroutines.yield()
        assertEquals((0 until 1000).map { "e$it" }, seen)
        collector.cancel()
    }
}
