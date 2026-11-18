package app.ottermsg.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {
    @Test fun hashVerifies() {
        val h = PinHash.hash("2580".toCharArray())
        assertEquals(PinHash.SALT + PinHash.OUT, h.size)
        assertTrue(PinHash.verify("2580".toCharArray(), h))
        assertFalse(PinHash.verify("2581".toCharArray(), h))
        assertFalse(PinHash.verify("25800".toCharArray(), h))
        assertFalse(PinHash.verify("2580".toCharArray(), h.copyOf(10)))
        assertFalse(PinHash.verify("2580".toCharArray(), ByteArray(0)))
        assertFalse(PinHash.verify("2580".toCharArray(), h + 0))
        assertFalse("random salt", h.contentEquals(PinHash.hash("2580".toCharArray())))
    }

    @Test fun parametersAreD121() {
        // Same output as a direct Argon2id(m 16 MiB, t 2, p 1) over the UTF-8 PIN with the stored salt.
        val salt = ByteArray(PinHash.SALT) { it.toByte() }
        val h = PinHash.hash("12345678".toCharArray(), salt)
        val direct = BackupKeys.argon2id("12345678".toByteArray(), salt, 16 * 1024, 2, 1, PinHash.OUT)
        assertTrue(h.copyOfRange(PinHash.SALT, h.size).contentEquals(direct))
        assertTrue(h.copyOfRange(0, PinHash.SALT).contentEquals(salt))
    }

    @Test fun hashNeverContainsThePin() {
        val pin = "13572468"
        repeat(5) {
            val h = PinHash.hash(pin.toCharArray())
            for (enc in listOf(pin.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_16BE), pin.toByteArray(Charsets.UTF_16LE)))
                assertFalse(containsRun(h, enc.copyOf(4)))
        }
    }

    @Test fun pinArrayIsLeftToTheCaller() {
        val pin = "2580".toCharArray()
        PinHash.hash(pin)
        assertEquals("2580", String(pin))
    }

    private fun containsRun(hay: ByteArray, needle: ByteArray): Boolean =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
}
