package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Rekey
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RekeyVerifierTest {
    private val net = Ed25519.keyPairFromSeed(ByteArray(32) { 9 })
    private val thief = Ed25519.keyPairFromSeed(ByteArray(32) { 1 })
    private val nodeKey = Ed25519.keyPairFromSeed(ByteArray(32) { 0x42 })
    private val otherNet = Ed25519.keyPairFromSeed(ByteArray(32) { 7 })
    private val netId = Ids.id16(net.publicKey)
    private val ctx = SigContext(netId, 0)
    private val oldId = Ids.id16(thief.publicKey)
    private val newId = ByteArray(16) { 5 }

    private fun payload(old: ByteArray = oldId, new: ByteArray = newId) = Rekey.newBuilder().setOldUserId(ByteString.copyFrom(old))
        .setNewUserId(ByteString.copyFrom(new)).build().toByteArray()
    private fun rekey(signer: Ed25519.KeyPair = net, author: ByteArray = netId, payload: ByteArray = payload(), expiresMs: Long = 0,
                      type: RecordType = RecordType.REKEY, sigCtx: SigContext = ctx) =
        RecordCodec.sign(RecordCodec.body(type, author, payload, expiresMs = expiresMs), signer, sigCtx)

    @Test fun networkSignedIsAccepted() {
        val k = RekeyVerifier.verify(rekey(), net.publicKey, ctx)
        assertNotNull(k)
        k!!
        assertArrayEquals(oldId, k.oldUserId.toByteArray())
        assertArrayEquals(newId, k.newUserId.toByteArray())
    }

    @Test fun v2SignedIsAccepted() =
        assertNotNull(RekeyVerifier.verify(rekey(sigCtx = SigContext(netId, 1)), net.publicKey, SigContext(netId, 1)))

    @Test fun oldAccountCannotRekeyItself() =
        assertNull(RekeyVerifier.verify(rekey(signer = thief, author = oldId), net.publicKey, ctx))

    @Test fun networkAuthorWithForeignSignature() =
        assertNull(RekeyVerifier.verify(rekey(signer = thief), net.publicKey, ctx))

    @Test fun nodeKeyIsNotTheNetwork() {
        assertNull(RekeyVerifier.verify(rekey(signer = nodeKey), net.publicKey, ctx))
        assertNull(RekeyVerifier.verify(rekey(signer = nodeKey, author = Ids.id16(nodeKey.publicKey)), net.publicKey, ctx))
    }

    @Test fun anotherNetworksRekeyIsIgnored() {
        val otherCtx = SigContext(Ids.id16(otherNet.publicKey), 1)
        assertNull(RekeyVerifier.verify(rekey(signer = otherNet, author = Ids.id16(otherNet.publicKey), sigCtx = otherCtx), net.publicKey, ctx))
        // our network's id as author, signed v2 for the other network: wrong signature
        assertNull(RekeyVerifier.verify(rekey(signer = otherNet, sigCtx = otherCtx), net.publicKey, ctx))
    }

    @Test fun malformedPayloadsAreRefused() {
        assertNull(RekeyVerifier.verify(rekey(payload = payload(new = oldId)), net.publicKey, ctx))
        assertNull(RekeyVerifier.verify(rekey(payload = payload(old = ByteArray(15))), net.publicKey, ctx))
        assertNull(RekeyVerifier.verify(rekey(payload = byteArrayOf(0x0a, 0x7f)), net.publicKey, ctx))
        assertNull(RekeyVerifier.verify(rekey(expiresMs = 5), net.publicKey, ctx))
        assertNull(RekeyVerifier.verify(rekey(type = RecordType.REVOCATION), net.publicKey, ctx))
    }
}
