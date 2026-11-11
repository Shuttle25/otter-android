package app.ottermsg.core.network

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Trusts exactly the leaf certificates whose SubjectPublicKeyInfo SHA-256 is pinned (docs/11 D27). */
class PinnedTrustManager(private val pins: List<ByteArray>) : X509TrustManager {
    init { require(pins.isNotEmpty()) }

    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        leaf.checkValidity()
        val pin = spkiPin(leaf)
        if (pins.none { MessageDigest.isEqual(it, pin) }) throw CertificateException("certificate does not match the pinned key")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = throw CertificateException("not supported")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedTrustManager), null) }
}

/** SHA-256 of the certificate's SubjectPublicKeyInfo (same as Go `pinning.SPKIPin`). */
fun spkiPin(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
