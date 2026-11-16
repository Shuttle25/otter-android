package app.ottermsg.core.data.account

import app.ottermsg.core.crypto.Ed25519
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.sync.SigContexts
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import org.signal.libsignal.protocol.IdentityKeyPair

class LocalAccount(
    val userId: ByteArray, val username: String, val displayName: String,
    val seed: ByteArray, val accountKey: Ed25519.KeyPair, val identity: IdentityKeyPair, val registrationId: Int,
    /** Time of registration: copied into every DIRECTORY_ENTRY version as registered_ms (D40). */
    val createdMs: Long,
    private val sig: SigContexts,
) {
    fun copyWith(displayName: String) =
        LocalAccount(userId, username, displayName, seed, accountKey, identity, registrationId, createdMs, sig)

    /** Signs one of our records under the current network context: v1 until the cut-over manifest, then v2 (D109). */
    suspend fun sign(body: RecordBody): Record = RecordCodec.sign(body, accountKey, sig.sigContext())
}
