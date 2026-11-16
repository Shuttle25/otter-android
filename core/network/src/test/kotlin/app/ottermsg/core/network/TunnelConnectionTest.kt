package app.ottermsg.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * D114: restore through a no_backups node's /tunnel. The phone runs its own TLS to the target node inside the relayed
 * stream and pins the TARGET's certificate; the relay only carries ciphertext.
 */
class TunnelConnectionTest {
    private val heldF = HeldCertificate.Builder().addSubjectAlternativeName("f1.test").build()
    private val heldEvil = HeldCertificate.Builder().addSubjectAlternativeName("f1.test").build()
    private val f1 = MockWebServer()
    private val evil = MockWebServer()
    private val ru = MockWebServer()
    /** Application messages the server behind the tunnel received from the phone. */
    private val received = AtomicInteger()
    private val relayClosed = CountDownLatch(1)

    private fun tls(s: MockWebServer, h: HeldCertificate) {
        s.useHttps(HandshakeCertificates.Builder().heldCertificate(h).build().sslSocketFactory())
        s.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("hello".encodeUtf8())
                webSocket.send(ByteArray(100_000) { it.toByte() }.toByteString()) // several relay chunks
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                received.incrementAndGet()
                webSocket.send("got ${bytes.size}".encodeUtf8())
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())
        s.start()
    }

    /** The no_backups node: pipes the phone's WebSocket bytes to [target]'s TCP port (D114). */
    private fun relayTo(target: MockWebServer) {
        ru.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            lateinit var sock: Socket
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sock = Socket(target.hostName, target.port)
                thread {
                    val buf = ByteArray(16384)
                    try {
                        while (true) { val n = sock.getInputStream().read(buf); if (n < 0) break; webSocket.send(buf.toByteString(0, n)) }
                    } catch (_: Exception) {}
                    webSocket.close(1000, null)
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try { sock.getOutputStream().write(bytes.toByteArray()); sock.getOutputStream().flush() } catch (_: Exception) {}
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { sock.close(); relayClosed.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { sock.close(); relayClosed.countDown() }
        }).build())
        ru.start()
    }

    @After fun tearDown() { f1.close(); evil.close(); ru.close() }

    private fun viaUrl() = ru.url("/x").toString().replace("http", "ws") + "/tunnel?to=01"

    private fun candidate(target: MockWebServer, pins: List<ByteArray>) = Candidate(ByteArray(16) { 1 }, "f1 via ru",
        "wss://f1.test:${target.port}/x", "f1.test", pins, 0,
        via = Candidate(ByteArray(16) { 2 }, "ru", viaUrl(), ru.hostName, emptyList(), 0))

    @Test fun pinnedTlsInsideTheTunnel() = runBlocking {
        tls(f1, heldF); relayTo(f1)
        val conn = OkHttpTransport().connect(candidate(f1, listOf(spkiPin(heldF.certificate))))
        assertEquals("hello", String(withTimeout(5_000) { conn.incoming.receive() }))
        assertArrayEquals(ByteArray(100_000) { it.toByte() }, withTimeout(5_000) { conn.incoming.receive() })
        conn.send(ByteArray(200_000) { 7 }) // the phone's messages are cut into ≤ 16 KiB tunnel messages
        assertEquals("got 200000", String(withTimeout(5_000) { conn.incoming.receive() }))
        assertEquals("/x/tunnel?to=01", ru.takeRequest(1, TimeUnit.SECONDS)!!.target)
        conn.close()
        assertTrue("closing the connection closes the tunnel", relayClosed.await(10, TimeUnit.SECONDS))
    }

    @Test fun otherCertificateBehindTheTunnelFails() {
        tls(evil, heldEvil); relayTo(evil) // a hostile relay ends the stream in a TLS server of its own (another certificate for the target's name)
        assertThrows(SSLHandshakeException::class.java) { runBlocking { OkHttpTransport().connect(candidate(evil, listOf(spkiPin(heldF.certificate)))) } }
        assertEquals("nothing reaches the impostor", 0, evil.requestCount)
        assertEquals(0, received.get())
    }

    @Test fun relayAnsweringInsteadOfTunnellingIsRefused() {
        // the relay accepts the WebSocket but speaks itself (a fake node hello) instead of carrying a TLS stream
        ru.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("HTTP/1.1 101 hello from the relay\r\n\r\n".encodeUtf8().toByteArray().toByteString()) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { received.incrementAndGet() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())
        ru.start()
        tls(f1, heldF)
        assertThrows(Exception::class.java) { runBlocking { withTimeout(10_000) { OkHttpTransport().connect(candidate(f1, listOf(spkiPin(heldF.certificate)))) } } }
        assertEquals(0, f1.requestCount)
    }

    @Test fun relayWithoutPeerSessionIsRefused() {
        ru.enqueue(MockResponse.Builder().code(503).body("node not connected").build())
        ru.start()
        tls(f1, heldF)
        assertThrows(Exception::class.java) { runBlocking { OkHttpTransport().connect(candidate(f1, listOf(spkiPin(heldF.certificate)))) } }
        assertEquals(0, f1.requestCount)
    }

    @Test fun unpinnedTargetIsRefused() {
        tls(f1, heldF); relayTo(f1)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { OkHttpTransport().connect(candidate(f1, emptyList())) } }
        assertEquals("refused before connecting", 0, ru.requestCount)
    }

    @Test fun tunnelKeyDiffersFromTheDirectKey() {
        tls(f1, heldF); ru.start()
        val pins = listOf(spkiPin(heldF.certificate))
        val direct = Candidate(ByteArray(16) { 1 }, "f1", "wss://f1.test:${f1.port}/x", "f1.test", pins, 0)
        assertTrue(direct.key != candidate(f1, pins).key)
    }
}
