package app.ottermsg.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Bip39Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val vectors = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000" to
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
        "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to
            "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title",
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote",
    )

    @Test fun standardVectors() {
        for ((e, m) in vectors) {
            assertEquals(m, Bip39.toMnemonic(hex(e)).joinToString(" "))
            assertArrayEquals(hex(e), Bip39.toEntropy(m.split(" ")))
        }
    }

    @Test(expected = IllegalArgumentException::class) fun badChecksum() {
        Bip39.toEntropy(("abandon ".repeat(24)).trim().split(" "))
    }

    @Test(expected = IllegalArgumentException::class) fun unknownWord() {
        Bip39.toEntropy((List(23) { "abandon" } + "otterword"))
    }

    @Test(expected = IllegalArgumentException::class) fun wrongCount() {
        Bip39.toEntropy(List(12) { "abandon" })
    }

    @Test fun normalizesCaseAndSpaces() {
        val m = vectors[0].second.uppercase().split(" ").map { " $it " }
        assertArrayEquals(ByteArray(32), Bip39.toEntropy(m))
    }
}
