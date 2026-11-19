package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestVerifierTest {
    private val net = Ed25519.keyPairFromSeed(ByteArray(32) { 1 })
    private val other = Ed25519.keyPairFromSeed(ByteArray(32) { 2 })

    private fun signed(version: Long, signer: Ed25519.KeyPair = net, author: ByteArray = Ids.id16(net.publicKey),
                       networkPub: ByteArray = net.publicKey, type: RecordType = RecordType.MANIFEST, ctx: SigContext = SigContext(Ids.id16(net.publicKey), 0)): ByteArray {
        val m = Manifest.newBuilder().setVersion(version).setNetworkPub(com.google.protobuf.ByteString.copyFrom(networkPub)).build()
        return RecordCodec.sign(RecordCodec.body(type, author, m.toByteArray()), signer, ctx).toByteArray()
    }

    @Test fun acceptsNewer() { assertEquals(5L, ManifestVerifier.verify(signed(5), net.publicKey, 4, 0).version) }
    @Test fun rejectsSameVersion() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(4), net.publicKey, 4, 0) } }
    @Test fun rejectsWrongSigner() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, signer = other), net.publicKey, 0, 0) } }
    @Test fun rejectsWrongAuthor() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, author = ByteArray(16)), net.publicKey, 0, 0) } }
    @Test fun rejectsForeignNetworkPub() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, networkPub = other.publicKey), net.publicKey, 0, 0) } }
    @Test fun rejectsWrongType() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, type = RecordType.ACK), net.publicKey, 0, 0) } }
    @Test fun rejectsGarbage() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(byteArrayOf(1, 2, 3), net.publicKey, 0, 0) } }

    @Test fun acceptsV2() { assertEquals(5L, ManifestVerifier.verify(signed(5, ctx = SigContext(Ids.id16(net.publicKey), 1)), net.publicKey, 4, 0).version) }
    @Test fun rejectsV1AfterCutover() {
        // created now (> 1): a v1 manifest signed after the cut-over is refused
        assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5), net.publicKey, 4, v1UntilMs = 1) }
    }
    @Test fun rejectsV2ForAnotherNetwork() {
        assertThrows(ManifestException::class.java) {
            ManifestVerifier.verify(signed(5, ctx = SigContext(ByteArray(16) { 9 }, 1)), net.publicKey, 4, 0)
        }
    }

    // D139: «Сеть изменилась» only for a manifest validly signed by another network's own key
    private fun foreign(ctx: SigContext = SigContext(Ids.id16(other.publicKey), 0)) =
        signed(5, signer = other, author = Ids.id16(other.publicKey), networkPub = other.publicKey, ctx = ctx)

    @Test fun foreignNetworkWhenSelfSignedByAnotherKey() {
        assertTrue(ManifestVerifier.foreignNetwork(foreign(), net.publicKey))
        assertTrue(ManifestVerifier.foreignNetwork(foreign(SigContext(Ids.id16(other.publicKey), 1)), net.publicKey)) // v2
    }
    @Test fun brokenSignatureIsNotForeign() {
        val rec = app.ottermsg.proto.v1.Record.parseFrom(foreign())
        val sig = rec.signature.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val flipped = rec.toBuilder().setSignature(com.google.protobuf.ByteString.copyFrom(sig)).build().toByteArray()
        assertFalse(ManifestVerifier.foreignNetwork(flipped, net.publicKey))
    }
    @Test fun ourOwnManifestIsNotForeign() { assertFalse(ManifestVerifier.foreignNetwork(signed(5), net.publicKey)) }
    @Test fun foreignClaimSignedByOurKeyOrGarbageIsNotForeign() {
        // names another network but is signed by ours: not "validly self-signed by a different key"
        assertFalse(ManifestVerifier.foreignNetwork(signed(5, networkPub = other.publicKey), net.publicKey))
        assertFalse(ManifestVerifier.foreignNetwork(byteArrayOf(1, 2, 3), net.publicKey))
        assertFalse(ManifestVerifier.foreignNetwork(signed(5, signer = other, author = Ids.id16(other.publicKey),
            networkPub = other.publicKey, type = RecordType.ACK, ctx = SigContext(Ids.id16(other.publicKey), 0)), net.publicKey))
    }
}
