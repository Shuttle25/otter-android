package app.ottermsg.core.network

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class PinnedTrustTest {
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val cert = held.certificate

    @Test fun matchingPinAccepted() {
        PinnedTrustManager(listOf(ByteArray(32), spkiPin(cert))).checkServerTrusted(arrayOf(cert), "ECDHE")
    }

    @Test fun wrongPinRejected() {
        assertThrows(CertificateException::class.java) { PinnedTrustManager(listOf(ByteArray(32))).checkServerTrusted(arrayOf(cert), "ECDHE") }
    }

    @Test fun emptyChainRejected() {
        assertThrows(CertificateException::class.java) { PinnedTrustManager(listOf(spkiPin(cert))).checkServerTrusted(emptyArray(), "ECDHE") }
    }

    @Test fun expiredRejected() {
        val old = HeldCertificate.Builder().addSubjectAlternativeName("localhost").validityInterval(1_000, 2_000).build().certificate
        assertThrows(java.security.cert.CertificateExpiredException::class.java) {
            PinnedTrustManager(listOf(spkiPin(old))).checkServerTrusted(arrayOf(old), "ECDHE")
        }
    }

    @Test fun samePinAsGo() {
        val v: Map<String, String> = run {
            val text = javaClass.getResource("/vectors/v1.json")!!.readText()
            Regex("\"(\\w+)\":\\s*\"?([^\",\\n]*)\"?").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        }
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val goCert = CertificateFactory.getInstance("X.509").generateCertificate(hex(v.getValue("tls_cert_der_hex")).inputStream()) as X509Certificate
        assertArrayEquals(hex(v.getValue("tls_spki_pin_hex")), spkiPin(goCert))
    }
}
