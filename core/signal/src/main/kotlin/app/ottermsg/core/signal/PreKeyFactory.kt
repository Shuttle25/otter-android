package app.ottermsg.core.signal

import app.ottermsg.proto.v1.OneTimePreKey
import app.ottermsg.proto.v1.SignedPreKey
import com.google.protobuf.ByteString
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/** Generates prekeys, stores private parts in [OtterProtocolStore], returns the public protos. */
object PreKeyFactory {
    private fun bs(b: ByteArray) = ByteString.copyFrom(b)

    fun signed(store: OtterProtocolStore, id: Int, nowMs: Long): SignedPreKey {
        val kp = ECKeyPair.generate()
        val pub = kp.publicKey.serialize()
        val sig = store.identityKeyPair.privateKey.calculateSignature(pub)
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, nowMs, kp, sig))
        return SignedPreKey.newBuilder().setId(id).setPublicKey(bs(pub)).setSignature(bs(sig)).build()
    }

    fun kyberLastResort(store: OtterProtocolStore, id: Int, nowMs: Long): SignedPreKey {
        val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val pub = kp.publicKey.serialize()
        val sig = store.identityKeyPair.privateKey.calculateSignature(pub)
        store.storeKyberPreKey(id, KyberPreKeyRecord(id, nowMs, kp, sig))
        return SignedPreKey.newBuilder().setId(id).setPublicKey(bs(pub)).setSignature(bs(sig)).build()
    }

    fun oneTime(store: OtterProtocolStore, firstId: Int, count: Int, ownerNode: ByteArray): List<OneTimePreKey> =
        (0 until count).map { i ->
            val id = firstId + i
            val kp = ECKeyPair.generate()
            store.storePreKey(id, PreKeyRecord(id, kp))
            OneTimePreKey.newBuilder().setId(id).setPublicKey(bs(kp.publicKey.serialize())).setOwnerNode(bs(ownerNode)).build()
        }
}
