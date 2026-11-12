package app.ottermsg.core.data.messaging

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.OutboxEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.SessionReset
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** SessionReset (D53): after a restore, and when a contact's messages can't be decrypted. */
@Singleton
class SessionResets @Inject constructor(
    private val db: OtterDatabase, private val signal: SignalService, private val outbox: Outbox, private val clock: Clock,
) {
    private fun content() = Content.newBuilder().setSessionReset(SessionReset.getDefaultInstance()).setSentMs(clock.now()).build().toByteArray()

    /** Drops our session with [peer] (the next send builds a fresh one from its prekeys) and queues a reset. */
    suspend fun send(peer: ByteArray) {
        signal.withSessions { store, s -> store.deleteSession(s.address(peer)) }
        db.outboxDao().insert(OutboxEntity(recipientUserId = peer, contentBytes = content(), messageLocalId = null,
            urgent = false, createdMs = clock.now()))
        db.contactDao().setResetMs(peer, clock.now())
        outbox.kick()
    }

    /** Throttled reset after a decryption failure: at most once per contact per hour. */
    suspend fun onUndecryptable(peer: ByteArray) {
        val c = db.contactDao().get(peer) ?: return
        if (c.revoked || c.resetMs > clock.now() - HOUR_MS) return
        send(peer)
    }

    /** After a restore: every contact. */
    suspend fun sendToAll() { db.contactDao().all().filter { !it.revoked }.forEach { send(it.userId) } }

    /** A contact restored their account (or lost our session): their new session is already active. */
    suspend fun onReceived(sender: ByteArray) {
        db.groupDao().deleteSharedWithMember(sender) // our sender keys are re-sent to them with the next group message
        val cid = db.conversationDao().byPeer(sender)?.id
        if (cid != null) {
            db.messageDao().insert(MessageEntity(messageId = ByteArray(16).also(SecureRandom()::nextBytes), conversationId = cid,
                senderId = sender, outgoing = false, sentMs = clock.now(), receivedMs = clock.now(), body = "",
                status = MessageStatus.DELIVERED, kind = MessageKind.SYSTEM_RESET))
            db.conversationDao().touch(cid, clock.now())
        }
        outbox.kick()
    }

    companion object { const val HOUR_MS = 3_600_000L }
}
