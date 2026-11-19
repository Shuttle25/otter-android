package app.ottermsg.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkCodeTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Shared with the Go node (`testdata/vectors/network_code.json`, D139). */
    @Test fun matchesSharedVectors() {
        val text = javaClass.getResource("/vectors/network_code.json")!!.readText()
        val vectors = Regex("\"network_pub\":\\s*\"([0-9a-f]+)\",\\s*\"code\":\\s*\"([0-9 ]+)\"").findAll(text)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(3, vectors.size)
        vectors.forEach { (pub, code) -> assertEquals(pub, code, NetworkCode.of(hex(pub))) }
    }

    @Test fun spokenDigitByDigit() {
        assertEquals("9 2 1 4 4 1 8 3 6 6 5 4 4 8 3", NetworkCode.spoken("92144 18366 54483"))
    }
}
