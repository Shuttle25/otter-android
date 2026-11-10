package app.ottermsg.core.signal

import app.ottermsg.core.signal.SignalKeyValue.Table
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * Trust policy: TOFU per user (address.name). Identity changes are accepted but reported through
 * [onIdentityChanged] so the UI can warn and clear "verified" (docs/04 "Key verification").
 */
class OtterProtocolStore(
    private val identity: IdentityKeyPair,
    private val registrationId: Int,
    private val kv: SignalKeyValue,
    private val onIdentityChanged: (SignalProtocolAddress) -> Unit = {},
) : SignalProtocolStore {
    private fun key(a: SignalProtocolAddress) = "${a.name}.${a.deviceId}"

    override fun getIdentityKeyPair(): IdentityKeyPair = identity
    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val new = identityKey.serialize()
        val old = kv.get(Table.IDENTITY, address.name)
        kv.put(Table.IDENTITY, address.name, new)
        if (old != null && !old.contentEquals(new)) {
            onIdentityChanged(address)
            return IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
        return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction) = true
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = kv.get(Table.IDENTITY, address.name)?.let { IdentityKey(it) }

    override fun loadPreKey(preKeyId: Int) = kv.get(Table.PREKEY, "$preKeyId")?.let { PreKeyRecord(it) } ?: throw InvalidKeyIdException("no prekey $preKeyId")
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = kv.put(Table.PREKEY, "$preKeyId", record.serialize())
    override fun containsPreKey(preKeyId: Int) = kv.get(Table.PREKEY, "$preKeyId") != null
    override fun removePreKey(preKeyId: Int) = kv.delete(Table.PREKEY, "$preKeyId")

    override fun loadSignedPreKey(signedPreKeyId: Int) =
        kv.get(Table.SIGNED_PREKEY, "$signedPreKeyId")?.let { SignedPreKeyRecord(it) } ?: throw InvalidKeyIdException("no signed prekey $signedPreKeyId")
    override fun loadSignedPreKeys() = kv.keys(Table.SIGNED_PREKEY).map { SignedPreKeyRecord(kv.get(Table.SIGNED_PREKEY, it)!!) }
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = kv.put(Table.SIGNED_PREKEY, "$signedPreKeyId", record.serialize())
    override fun containsSignedPreKey(signedPreKeyId: Int) = kv.get(Table.SIGNED_PREKEY, "$signedPreKeyId") != null
    override fun removeSignedPreKey(signedPreKeyId: Int) = kv.delete(Table.SIGNED_PREKEY, "$signedPreKeyId")

    override fun loadKyberPreKey(kyberPreKeyId: Int) =
        kv.get(Table.KYBER_PREKEY, "$kyberPreKeyId")?.let { KyberPreKeyRecord(it) } ?: throw InvalidKeyIdException("no kyber prekey $kyberPreKeyId")
    override fun loadKyberPreKeys() = kv.keys(Table.KYBER_PREKEY).map { KyberPreKeyRecord(kv.get(Table.KYBER_PREKEY, it)!!) }
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = kv.put(Table.KYBER_PREKEY, "$kyberPreKeyId", record.serialize())
    override fun containsKyberPreKey(kyberPreKeyId: Int) = kv.get(Table.KYBER_PREKEY, "$kyberPreKeyId") != null
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val k = "$kyberPreKeyId:$signedPreKeyId:${baseKey.serialize().toHex()}"
        if (kv.get(Table.KYBER_USED, k) != null) throw ReusedBaseKeyException("kyber prekey $kyberPreKeyId reused with same base key")
        kv.put(Table.KYBER_USED, k, byteArrayOf(1))
    }

    override fun loadSession(address: SignalProtocolAddress) = kv.get(Table.SESSION, key(address))?.let { SessionRecord(it) } ?: SessionRecord()
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>) =
        addresses.map { kv.get(Table.SESSION, key(it))?.let { b -> SessionRecord(b) } ?: throw NoSessionException("no session for $it") }
    override fun getSubDeviceSessions(name: String) =
        kv.keys(Table.SESSION).filter { it.startsWith("$name.") }.map { it.substringAfterLast('.').toInt() }.filter { it != 1 }
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = kv.put(Table.SESSION, key(address), record.serialize())
    override fun containsSession(address: SignalProtocolAddress) =
        kv.get(Table.SESSION, key(address))?.let { SessionRecord(it).hasSenderChain() } ?: false
    override fun deleteSession(address: SignalProtocolAddress) = kv.delete(Table.SESSION, key(address))
    override fun deleteAllSessions(name: String) = kv.keys(Table.SESSION).filter { it.startsWith("$name.") }.forEach { kv.delete(Table.SESSION, it) }

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        kv.put(Table.SENDER_KEY, "${key(sender)}:$distributionId", record.serialize())
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        kv.get(Table.SENDER_KEY, "${key(sender)}:$distributionId")?.let { SenderKeyRecord(it) }
}
