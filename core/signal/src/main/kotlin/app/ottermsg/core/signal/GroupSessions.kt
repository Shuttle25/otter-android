package app.ottermsg.core.signal

import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SenderKeyMessage
import java.util.UUID

/**
 * Sender-key group sessions. Each member owns one sender key per distribution id; a membership removal switches
 * everyone to a new distribution id (rotation), so a removed member cannot read later messages.
 * Not thread-safe: callers serialize access with the same lock as [SignalSessions].
 */
class GroupSessions(private val store: OtterProtocolStore, localUserId: ByteArray, localDevice: Int = 1) {
    private val local = SignalProtocolAddress(localUserId.toHex(), localDevice)

    fun address(userId: ByteArray, device: Int = 1) = SignalProtocolAddress(userId.toHex(), device)

    /** SKDM bytes for our own sender key under [distId]; creates the key on first use. */
    fun distribution(distId: UUID): ByteArray = GroupSessionBuilder(store).create(local, distId).serialize()

    /** Stores a member's sender key from their SKDM. */
    fun process(sender: SignalProtocolAddress, skdm: ByteArray) =
        GroupSessionBuilder(store).process(sender, SenderKeyDistributionMessage(skdm))

    fun encrypt(distId: UUID, plaintext: ByteArray): ByteArray = GroupCipher(store, local).encrypt(distId, plaintext).serialize()

    /** Throws NoSessionException before the sender's SKDM was processed, DuplicateMessageException on replays. */
    fun decrypt(sender: SignalProtocolAddress, ciphertext: ByteArray): ByteArray = GroupCipher(store, sender).decrypt(ciphertext)

    fun distributionIdOf(ciphertext: ByteArray): UUID = SenderKeyMessage(ciphertext).distributionId
}
