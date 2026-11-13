package app.ottermsg.core.network

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Test

class FailureClassTest {
    @Test fun classifies() {
        assertEquals(FailureClass.OK, classify(null, 0))
        assertEquals(FailureClass.TIMEOUT, classify(SocketTimeoutException("t"), 0))
        assertEquals(FailureClass.REFUSED, classify(ConnectException("refused"), 0))
        assertEquals(FailureClass.TLS, classify(SSLHandshakeException("pin"), 0))
        assertEquals(FailureClass.TLS, classify(IOException("wrap", CertificateException("x")), 0))
        assertEquals(FailureClass.RESET, classify(EOFException(), 10))
        assertEquals(FailureClass.RESET_AFTER_KB, classify(IOException("reset"), 4096))
        assertEquals(FailureClass.HANDSHAKE, classify(HandshakeFailed("no hello"), 0))
    }

    @Test fun logNameDropsTheSecretPath() {
        assertEquals("wss://1.2.3.4:27443", cand("wss://1.2.3.4:27443/s3cr3t/ws").logName())
        assertEquals("wss://host", cand("wss://host").logName())
    }
}
