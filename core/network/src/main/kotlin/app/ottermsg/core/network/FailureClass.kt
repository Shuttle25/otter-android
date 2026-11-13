package app.ottermsg.core.network

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Connection outcome classes shared with the nodes (D73). */
enum class FailureClass(val wire: String) {
    OK("ok"), TIMEOUT("timeout"), REFUSED("refused"), RESET("reset"), RESET_AFTER_KB("reset_after_kb"),
    TLS("tls"), HANDSHAKE("handshake"),
}

/** The transport connected but the node's hello never came / was invalid. */
class HandshakeFailed(message: String) : Exception(message)

/** A session that dies with an error after at least this many received bytes looks like DPI throttling (D73). */
const val THROTTLE_BYTES = 1024L

fun classify(t: Throwable?, bytesIn: Long): FailureClass {
    if (t == null) return FailureClass.OK
    var e: Throwable? = t
    while (e != null) {
        when (e) {
            is HandshakeFailed -> return FailureClass.HANDSHAKE
            is SocketTimeoutException -> return FailureClass.TIMEOUT
            is ConnectException -> return FailureClass.REFUSED
            is SSLException, is CertificateException -> return FailureClass.TLS
        }
        e = e.cause
    }
    return if (bytesIn >= THROTTLE_BYTES) FailureClass.RESET_AFTER_KB else FailureClass.RESET
}
