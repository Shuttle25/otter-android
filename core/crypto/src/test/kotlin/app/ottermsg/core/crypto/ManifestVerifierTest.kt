package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Manifest
import app.ottermsg.proto.v1.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ManifestVerifierTest {
    private val net = Ed25519.keyPairFromSeed(ByteArray(32) { 1 })
    private val other = Ed25519.keyPairFromSeed(ByteArray(32) { 2 })

    private fun signed(version: Long, signer: Ed25519.KeyPair = net, author: ByteArray = Ids.id16(net.publicKey),
                       networkPub: ByteArray = net.publicKey, type: RecordType = RecordType.MANIFEST): ByteArray {
        val m = Manifest.newBuilder().setVersion(version).setNetworkPub(com.google.protobuf.ByteString.copyFrom(networkPub)).build()
        return RecordCodec.sign(RecordCodec.body(type, author, m.toByteArray()), signer).toByteArray()
    }

    @Test fun acceptsNewer() { assertEquals(5L, ManifestVerifier.verify(signed(5), net.publicKey, 4).version) }
    @Test fun rejectsSameVersion() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(4), net.publicKey, 4) } }
    @Test fun rejectsWrongSigner() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, signer = other), net.publicKey, 0) } }
    @Test fun rejectsWrongAuthor() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, author = ByteArray(16)), net.publicKey, 0) } }
    @Test fun rejectsForeignNetworkPub() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, networkPub = other.publicKey), net.publicKey, 0) } }
    @Test fun rejectsWrongType() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(signed(5, type = RecordType.ACK), net.publicKey, 0) } }
    @Test fun rejectsGarbage() { assertThrows(ManifestException::class.java) { ManifestVerifier.verify(byteArrayOf(1, 2, 3), net.publicKey, 0) } }
}
