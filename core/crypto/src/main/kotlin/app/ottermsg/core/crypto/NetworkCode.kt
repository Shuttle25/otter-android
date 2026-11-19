package app.ottermsg.core.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The 15-digit network code (D139): first 8 bytes of SHA-256("otter-network-code" ‖ network_pub), big-endian and
 * unsigned (as Go's Uint64), mod 10^15, zero-padded and shown as `ddddd ddddd ddddd`.
 */
object NetworkCode {
    private val MOD = BigInteger.TEN.pow(15)

    fun of(networkPub: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").run { update("otter-network-code".toByteArray()); digest(networkPub) }
        val s = BigInteger(1, h.copyOf(8)).mod(MOD).toString().padStart(15, '0')
        return "${s.substring(0, 5)} ${s.substring(5, 10)} ${s.substring(10)}"
    }

    /** What TalkBack reads: the digits one by one ("9 2 1 4 4 …"), not "ninety-two thousand …". */
    fun spoken(code: String): String = code.filter { it.isDigit() }.toList().joinToString(" ")
}
