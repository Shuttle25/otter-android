package app.ottermsg.core.network

import java.security.MessageDigest

/** One way to reach a node: a URL plus the TLS pins that authenticate it (docs/05). */
class Candidate(
    val nodeId: ByteArray,
    val label: String,
    val url: String,
    val host: String,
    val pins: List<ByteArray>,
    /** Lower = preferred among candidates without history (manifest priority, then manifest order). */
    val priority: Int,
) {
    /** Identifies the pin set (one OkHttp client per pin set). Empty when unpinned. */
    val pinsKey: String = if (pins.isEmpty()) "" else
        MessageDigest.getInstance("SHA-256").digest(pins.sortedWith(::compareBytes).fold(ByteArray(0)) { a, b -> a + b }).toHex()

    /** Stable key for connection statistics: the same URL with other pins is a different endpoint. */
    val key: String = if (pinsKey.isEmpty()) url else "$url#$pinsKey"

    override fun toString() = "Candidate($label, $url)"

    private companion object {
        fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) { val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (d != 0) return d }
            return a.size - b.size
        }
        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
