package app.ottermsg.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

class PinnedConnectionTest {
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer()
    private val transport = OkHttpTransport()

    @Before fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start()
    }
    @After fun tearDown() = server.close()

    private fun candidate(pins: List<ByteArray>) =
        Candidate(ByteArray(16), "n", server.url("/").toString(), "localhost", pins, 0)

    private fun get(c: Candidate): Int {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        return transport.clientFor(c).newCall(Request.Builder().url(c.url).build()).execute().use { it.code }
    }

    @Test fun matchingPinConnects() = assertEquals(200, get(candidate(listOf(spkiPin(held.certificate)))))

    @Test fun wrongPinFails() {
        assertThrows(SSLHandshakeException::class.java) { get(candidate(listOf(ByteArray(32)))) }
    }

    @Test fun noPinsUsesSystemTrust() {
        assertThrows(SSLHandshakeException::class.java) { get(candidate(emptyList())) } // self-signed: not in the system store
    }

    @Test fun clientsAreCachedPerPinSet() {
        val a = transport.clientFor(candidate(listOf(spkiPin(held.certificate))))
        val b = transport.clientFor(candidate(listOf(spkiPin(held.certificate))))
        assertEquals(true, a === b)
    }
}
