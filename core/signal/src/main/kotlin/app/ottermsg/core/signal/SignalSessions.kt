package app.ottermsg.core.signal

import app.ottermsg.proto.v1.Envelope
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import app.ottermsg.proto.v1.PreKeyBundle as ProtoBundle
import org.signal.libsignal.protocol.state.PreKeyBundle as SignalBundle

/** 1:1 Signal sessions. Not thread-safe: callers serialize access (core:data uses one Mutex). */
class SignalSessions(private val store: OtterProtocolStore, localUserId: ByteArray, localDevice: Int = 1) {
    private val local = SignalProtocolAddress(localUserId.toHex(), localDevice)

    fun address(userId: ByteArray, device: Int = 1) = SignalProtocolAddress(userId.toHex(), device)

    fun hasSession(remote: SignalProtocolAddress) = store.containsSession(remote)

    fun processBundle(bundle: ProtoBundle, expectedIdentity: ByteArray) {
        check(bundle.identityPub.toByteArray().contentEquals(expectedIdentity)) { "prekey bundle identity differs from directory" }
        val ot = if (bundle.hasOneTime()) bundle.oneTime else null
        val b = SignalBundle(
            bundle.registrationId, bundle.deviceId,
            ot?.id ?: SignalBundle.NULL_PRE_KEY_ID, ot?.let { ECPublicKey(it.publicKey.toByteArray()) },
            bundle.signedPrekey.id, ECPublicKey(bundle.signedPrekey.publicKey.toByteArray()), bundle.signedPrekey.signature.toByteArray(),
            IdentityKey(bundle.identityPub.toByteArray()),
            bundle.kyberPrekey.id, KEMPublicKey(bundle.kyberPrekey.publicKey.toByteArray()), bundle.kyberPrekey.signature.toByteArray(),
        )
        // libsignal 0.106: SessionBuilder takes (store, remote, local) but SessionCipher takes (store, local, remote).
        SessionBuilder(store, address(bundle.userId.toByteArray(), bundle.deviceId), local).process(b)
    }

    fun encrypt(remote: SignalProtocolAddress, plaintext: ByteArray): Pair<Envelope.Kind, ByteArray> {
        val m = SessionCipher(store, local, remote).encrypt(plaintext)
        val kind = if (m.type == CiphertextMessage.PREKEY_TYPE) Envelope.Kind.PREKEY_SIGNAL else Envelope.Kind.SIGNAL
        return kind to m.serialize()
    }

    fun decrypt(remote: SignalProtocolAddress, kind: Envelope.Kind, ciphertext: ByteArray): ByteArray {
        val c = SessionCipher(store, local, remote)
        return when (kind) {
            Envelope.Kind.PREKEY_SIGNAL -> c.decrypt(PreKeySignalMessage(ciphertext))
            Envelope.Kind.SIGNAL -> c.decrypt(SignalMessage(ciphertext))
            else -> throw IllegalArgumentException("unsupported envelope kind $kind")
        }
    }
}
