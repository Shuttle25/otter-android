package app.ottermsg.core.data.directory

import androidx.room.withTransaction
import app.ottermsg.core.crypto.RekeyVerifier
import app.ottermsg.core.crypto.SigContext
import app.ottermsg.core.data.Clock
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.RekeyEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lost-phone re-keys (D111), as the nodes apply them (otter-server internal/store/rekey.go): a REKEY verified with the
 * network key revokes the old account (like a REVOCATION created at the earliest REKEY of it) and, for the winning
 * REKEY (later created_ms, then lower record key), the old account's direct chat continues with the new account. The
 * re-keyed person's own history is not recovered (owner Q5). [DirectorySync] stores and admits; this class persists
 * the REKEYs and moves the chats. Groups: [onApplied] (Task 55).
 */
@Singleton
class RekeyApplier @Inject constructor(private val db: OtterDatabase, private val clock: Clock) {
    private val rng = SecureRandom()

    /** Called after a chat moved from old to new (group work, Task 55); default no-op. */
    var onApplied: suspend (old: ByteArray, new: ByteArray) -> Unit = { _, _ -> }

    /** Stores the verified REKEYs of [records] (forged ones are ignored); returns every stored winner. */
    suspend fun store(records: List<Record>, networkPub: ByteArray, ctx: SigContext): List<RekeyEntity> {
        val dao = db.rekeyDao()
        for (r in records) {
            val k = RekeyVerifier.verify(r, networkPub, ctx) ?: continue
            val body = RecordBody.parseFrom(r.body)
            val old = k.oldUserId.toByteArray()
            val new = k.newUserId.toByteArray()
            val id = body.id.toByteArray()
            val cur = dao.get(old)
            if (cur == null) { dao.upsert(RekeyEntity(old, new, body.createdMs, id, revokedMs = body.createdMs)); continue }
            val revokedMs = minOf(cur.revokedMs, body.createdMs)
            val wins = body.createdMs > cur.createdMs ||
                (body.createdMs == cur.createdMs && Membership.compareUnsigned(id, cur.recordId) < 0)
            when {
                wins && cur.newUserId.contentEquals(new) -> dao.upsert(cur.copy(createdMs = body.createdMs, recordId = id, revokedMs = revokedMs))
                wins -> dao.upsert(RekeyEntity(old, new, body.createdMs, id, revokedMs)) // a later REKEY names another account
                revokedMs != cur.revokedMs -> dao.upsert(cur.copy(revokedMs = revokedMs))
            }
        }
        return dao.all()
    }

    /** Moves the chat of every winning REKEY not applied yet whose new account is a contact (or us). */
    suspend fun applyPending(me: ByteArray) {
        for (p in db.rekeyDao().pending()) {
            if (p.newUserId.contentEquals(me) || p.oldUserId.contentEquals(me)) { // our own re-key: nothing to move
                db.rekeyDao().markApplied(p.oldUserId, clock.now()); continue
            }
            if (db.contactDao().get(p.newUserId) == null) continue // its entry arrives with a later sync
            moveChat(p.oldUserId, p.newUserId)
            onApplied(p.oldUserId, p.newUserId)
        }
    }

    private suspend fun moveChat(old: ByteArray, new: ByteArray) = db.withTransaction {
        val contacts = db.contactDao()
        val prev = contacts.get(old)
        if (prev != null) {
            if (!prev.revoked) contacts.upsert(prev.copy(revoked = true))
            contacts.markIdentityChanged(new) // someone we knew, with a new key: unverified until checked again
        }
        // unsent mail to the lost key is dropped; its messages show as failed, and a retry goes to the new account
        val outbox = db.outboxDao()
        outbox.unsentMessageIdsTo(old).forEach { db.messageDao().markFailed(it) }
        outbox.deleteUnsentTo(old)
        val conv = db.conversationDao()
        val from = conv.byPeer(old)
        val into = conv.byPeer(new)
        val cid = when {
            from != null && into != null -> {
                db.messageDao().moveConversation(into.id, from.id)
                conv.delete(into.id)
                conv.movePeer(old, new)
                conv.setUnread(from.id, from.unread + into.unread)
                from.id
            }
            from != null -> { conv.movePeer(old, new); from.id }
            else -> into?.id
        }
        if (cid != null) {
            contacts.get(new)?.let { conv.setTitle(new, it.displayName) }
            val now = clock.now()
            db.messageDao().insert(MessageEntity(messageId = ByteArray(16).also(rng::nextBytes), conversationId = cid,
                senderId = new, outgoing = false, sentMs = now, receivedMs = now, body = "", status = MessageStatus.DELIVERED,
                kind = MessageKind.SYSTEM_KEY_CHANGED, readReceiptSent = true))
            conv.touch(cid, now)
        }
        db.rekeyDao().markApplied(old, clock.now())
    }
}
