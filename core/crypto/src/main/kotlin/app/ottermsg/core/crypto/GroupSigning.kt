package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.GroupState
import app.ottermsg.proto.v1.SignedGroupState
import com.google.protobuf.ByteString

/** Signed group state: Ed25519(signer account key, "otter-group-v1" || state bytes). */
object GroupSigning {
    private val PREFIX = "otter-group-v1".toByteArray()

    fun sign(state: GroupState, account: Ed25519.KeyPair): SignedGroupState {
        val bytes = state.toByteArray()
        return SignedGroupState.newBuilder()
            .setState(ByteString.copyFrom(bytes))
            .setSigner(ByteString.copyFrom(Ids.id16(account.publicKey)))
            .setSignature(ByteString.copyFrom(account.sign(PREFIX + bytes)))
            .build()
    }

    /** True if [signed] carries a valid signature by [accountPub] and its signer id is bound to that key. */
    fun verify(signed: SignedGroupState, accountPub: ByteArray): Boolean =
        signed.signer.toByteArray().contentEquals(Ids.id16(accountPub)) &&
            Ed25519.verify(accountPub, PREFIX + signed.state.toByteArray(), signed.signature.toByteArray())
}
