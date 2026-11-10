package app.ottermsg.core.database

import app.ottermsg.core.database.key.DatabaseKeyProvider
import app.ottermsg.core.database.key.KeyWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseKeyProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private class XorWrapper : KeyWrapper {
        var wraps = 0
        override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray().also { wraps++ }
        override fun unwrap(wrapped: ByteArray) = wrapped.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    @Test fun createsOnceAndReuses() {
        val w = XorWrapper()
        val file = File(tmp.root, "db.key")
        val k1 = DatabaseKeyProvider(file, w).getOrCreate()
        val k2 = DatabaseKeyProvider(file, w).getOrCreate()
        assertEquals(32, k1.size)
        assertArrayEquals(k1, k2)
        assertEquals(1, w.wraps)
        assertFalse(file.readBytes().contentEquals(k1)) // stored wrapped, not plain
    }
}
