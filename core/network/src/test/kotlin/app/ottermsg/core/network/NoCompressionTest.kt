package app.ottermsg.core.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ProtocolException

/** D93: the phone never negotiates permessage-deflate, so a node cannot send a decompression bomb. */
class NoCompressionTest {
    private val server = MockWebServer()
    private val quiet = object : WebSocketListener() {}
    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()
    private fun candidate() = Candidate(ByteArray(16), "n", server.url("/ws").toString().replaceFirst("http", "ws"), "localhost", emptyList(), 0)

    @Test fun neverOffersCompression() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(quiet).build())
        val c = OkHttpTransport().connect(candidate())
        assertNull(server.takeRequest().headers["Sec-WebSocket-Extensions"])
        c.close()
    }

    @Test fun refusesANodeThatTurnsCompressionOn() = runBlocking {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(quiet).addHeader("Sec-WebSocket-Extensions", "permessage-deflate").build())
        val e = runCatching { OkHttpTransport().connect(candidate()) }.exceptionOrNull()
        assertTrue("$e", e.oversize() == null && generateSequence(e) { it.cause }.any { it is ProtocolException })
    }
}
