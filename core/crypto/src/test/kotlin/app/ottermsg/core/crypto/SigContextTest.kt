package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.RecordType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigContextTest {
    private val user = Ed25519.keyPairFromSeed(ByteArray(32) { 1 })
    private val netA = Ids.id16(Ed25519.keyPairFromSeed(ByteArray(32) { 2 }).publicKey)
    private val netB = Ids.id16(Ed25519.keyPairFromSeed(ByteArray(32) { 3 }).publicKey)
    private fun body(created: Long) = RecordCodec.body(RecordType.ACK, Ids.id16(user.publicKey), byteArrayOf(1), createdMs = created)

    @Test fun v2IsBoundToTheNetwork() {
        val rec = RecordCodec.sign(body(10), user, SigContext(netA, v1UntilMs = 5))
        assertTrue(RecordCodec.verify(rec, user.publicKey, SigContext(netA, 5)))
        assertTrue(RecordCodec.verify(rec, user.publicKey, SigContext(netA, 0))) // v2 is accepted before the cut-over too
        assertFalse(RecordCodec.verify(rec, user.publicKey, SigContext(netB, 5))) // hostile: replay into network B
        assertFalse(RecordCodec.verify(rec, user.publicKey, SigContext(netB, 0)))
    }

    @Test fun v1OnlyBeforeTheCutover() {
        val pre = SigContext(netA, 0)
        assertTrue(RecordCodec.verify(RecordCodec.sign(body(10), user, pre), user.publicKey, pre))
        val cut = SigContext(netA, 100)
        assertTrue(RecordCodec.verify(RecordCodec.sign(body(99), user, pre), user.publicKey, cut))
        assertFalse(RecordCodec.verify(RecordCodec.sign(body(100), user, pre), user.publicKey, cut))
    }

    @Test fun signsV2OnlyAfterTheCutover() {
        assertFalse(SigContext(netA, 0).signV2)
        assertTrue(SigContext(netA, 1).signV2)
        val rec = RecordCodec.sign(body(10), user, SigContext(netA, 1))
        assertTrue(Ed25519.verify(user.publicKey, RecordCodec.messageV2(netA, rec.body.toByteArray()), rec.signature.toByteArray()))
        assertFalse(Ed25519.verify(user.publicKey, rec.body.toByteArray(), rec.signature.toByteArray()))
    }

    @Test fun ofManifest() {
        val net = Ed25519.keyPairFromSeed(ByteArray(32) { 2 }).publicKey
        val c = SigContext.of(net, Manifest.newBuilder().setSigV1UntilMs(42).build())
        assertArrayEquals(netA, c.networkId)
        assertEquals(42L, c.v1UntilMs)
        assertEquals(0L, SigContext.of(net, null).v1UntilMs)
    }

    @Test fun badSignatureLengthOrKey() {
        val rec = RecordCodec.sign(body(10), user, SigContext(netA, 0))
        val short = rec.toBuilder().setSignature(rec.signature.substring(0, 63)).build()
        assertFalse(RecordCodec.verify(short, user.publicKey, SigContext(netA, 0)))
        assertFalse(RecordCodec.verify(rec, ByteArray(31), SigContext(netA, 0)))
    }
}
