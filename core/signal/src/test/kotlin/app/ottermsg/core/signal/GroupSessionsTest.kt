package app.ottermsg.core.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.NoSessionException
import java.util.UUID

class GroupSessionsTest {
    private class Party(name: Byte) {
        val userId = ByteArray(16) { name }
        val store = OtterProtocolStore(IdentityKeys.fromSeed(ByteArray(32) { name }), 2000 + name, InMemorySignalKeyValue())
        val groups = GroupSessions(store, userId)
    }

    private val a = Party(1); private val b = Party(2); private val c = Party(3)
    private fun Party.from(p: Party) = groups.address(p.userId)

    @Test fun distributeEncryptDecrypt() {
        val dist = UUID.randomUUID()
        val skdm = a.groups.distribution(dist)
        val m1 = a.groups.encrypt(dist, "один".toByteArray())
        // before B processes A's SKDM
        assertThrows(NoSessionException::class.java) { b.groups.decrypt(b.from(a), m1) }

        b.groups.process(b.from(a), skdm)
        c.groups.process(c.from(a), skdm)
        val m2 = a.groups.encrypt(dist, "два".toByteArray())
        val m3 = a.groups.encrypt(dist, "три".toByteArray())
        assertEquals(dist, a.groups.distributionIdOf(m3))

        assertArrayEquals("один".toByteArray(), b.groups.decrypt(b.from(a), m1))
        assertArrayEquals("три".toByteArray(), b.groups.decrypt(b.from(a), m3)) // out of order
        assertArrayEquals("два".toByteArray(), b.groups.decrypt(b.from(a), m2))
        for (m in listOf(m3, m1, m2)) c.groups.decrypt(c.from(a), m)
        assertThrows(DuplicateMessageException::class.java) { b.groups.decrypt(b.from(a), m2) }
    }

    @Test fun rotationExcludesRemovedMember() {
        val old = UUID.randomUUID()
        val skdm = a.groups.distribution(old)
        b.groups.process(b.from(a), skdm); c.groups.process(c.from(a), skdm)
        c.groups.decrypt(c.from(a), a.groups.encrypt(old, "до".toByteArray()))

        // C removed: A rotates and shares the new key with B only
        val fresh = UUID.randomUUID()
        b.groups.process(b.from(a), a.groups.distribution(fresh))
        val after = a.groups.encrypt(fresh, "после".toByteArray())
        assertArrayEquals("после".toByteArray(), b.groups.decrypt(b.from(a), after))
        assertThrows(NoSessionException::class.java) { c.groups.decrypt(c.from(a), after) }
    }
}
