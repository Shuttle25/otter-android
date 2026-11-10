package app.ottermsg.core.signal

import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.PreKeyBundle
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.IdentityKeyStore

class SessionsTest {
    private val node = ByteArray(16) { 1 }

    private class Party(name: Byte) {
        val userId = ByteArray(16) { name }
        val identity = IdentityKeys.fromSeed(ByteArray(32) { name })
        val changes = mutableListOf<String>()
        val store = OtterProtocolStore(identity, 1000 + name, InMemorySignalKeyValue()) { changes += it.name }
        val sessions = SignalSessions(store, userId)
    }

    private fun publish(p: Party, withOneTime: Boolean): PreKeyBundle {
        val signed = PreKeyFactory.signed(p.store, 1, 0)
        val kyber = PreKeyFactory.kyberLastResort(p.store, 1, 0)
        val oneTime = PreKeyFactory.oneTime(p.store, 100, 1, node)
        return PreKeyBundle.newBuilder().setUserId(ByteString.copyFrom(p.userId)).setDeviceId(1)
            .setRegistrationId(p.store.localRegistrationId).setIdentityPub(ByteString.copyFrom(p.identity.publicKey.serialize()))
            .setSignedPrekey(signed).setKyberPrekey(kyber)
            .apply { if (withOneTime) setOneTime(oneTime[0]) }.build()
    }

    @Test fun conversation() {
        val alice = Party(1); val bob = Party(2)
        alice.sessions.processBundle(publish(bob, true), bob.identity.publicKey.serialize())
        val toBob = alice.sessions.address(bob.userId)
        val toAlice = bob.sessions.address(alice.userId)

        val m1 = alice.sessions.encrypt(toBob, "один".toByteArray())
        val m2 = alice.sessions.encrypt(toBob, "два".toByteArray())
        val m3 = alice.sessions.encrypt(toBob, "три".toByteArray())
        assertEquals(Envelope.Kind.PREKEY_SIGNAL, m1.first)
        assertArrayEquals("один".toByteArray(), bob.sessions.decrypt(toAlice, m1.first, m1.second))
        assertArrayEquals("три".toByteArray(), bob.sessions.decrypt(toAlice, m3.first, m3.second)) // out of order
        assertArrayEquals("два".toByteArray(), bob.sessions.decrypt(toAlice, m2.first, m2.second))
        assertThrows(DuplicateMessageException::class.java) { bob.sessions.decrypt(toAlice, m2.first, m2.second) }

        val r = bob.sessions.encrypt(toAlice, "ответ".toByteArray())
        assertEquals(Envelope.Kind.SIGNAL, r.first)
        assertArrayEquals("ответ".toByteArray(), alice.sessions.decrypt(toBob, r.first, r.second))
    }

    @Test fun lastResortOnly() {
        val alice = Party(3); val bob = Party(4)
        alice.sessions.processBundle(publish(bob, false), bob.identity.publicKey.serialize())
        val m = alice.sessions.encrypt(alice.sessions.address(bob.userId), "hi".toByteArray())
        assertArrayEquals("hi".toByteArray(), bob.sessions.decrypt(bob.sessions.address(alice.userId), m.first, m.second))
    }

    @Test fun identityMismatchRejected() {
        val alice = Party(5); val bob = Party(6)
        assertThrows(IllegalStateException::class.java) {
            alice.sessions.processBundle(publish(bob, true), alice.identity.publicKey.serialize())
        }
    }

    @Test fun kyberReuseDetected() {
        val bob = Party(7)
        val base = ECKeyPair.generate().publicKey
        bob.store.markKyberPreKeyUsed(1, 1, base)
        assertThrows(ReusedBaseKeyException::class.java) { bob.store.markKyberPreKeyUsed(1, 1, base) }
    }

    @Test fun identityChangeReported() {
        val alice = Party(8)
        val addr = alice.sessions.address(ByteArray(16) { 9 })
        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, alice.store.saveIdentity(addr, IdentityKeys.fromSeed(ByteArray(32) { 1 }).publicKey))
        assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, alice.store.saveIdentity(addr, IdentityKeys.fromSeed(ByteArray(32) { 2 }).publicKey))
        assertEquals(listOf(addr.name), alice.changes)
    }
}
