package app.ottermsg.core.data.calls

import androidx.room.withTransaction
import app.ottermsg.core.data.Clock
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local call lines in the 1:1 chat (D80). Never sent; never read-receipted (readReceiptSent = true, and
 * unreadReceiptsPending selects TEXT/PHOTO only); not notified (not in NotificationContent.SHOWN); not backed up.
 */
@Singleton
class CallLines @Inject constructor(private val db: OtterDatabase, private val clock: Clock) {
    private val rng = SecureRandom()

    /** Writes one line into the direct chat with [peer] (created if needed); a MISSED line counts as unread. Returns the chat id. */
    suspend fun write(peer: Peer, outcome: CallOutcome, durationS: Long, atMs: Long): Long = db.withTransaction {
        val now = clock.now()
        val cid = db.conversationDao().getOrCreateDirect(peer.id, peer.name, now)
        val missed = outcome == CallOutcome.MISSED
        // `outgoing` only aligns the line: the caller's side (DECLINED / FAILED are shown on the left).
        val outgoing = outcome == CallOutcome.OUTGOING || outcome == CallOutcome.NO_ANSWER || outcome == CallOutcome.BUSY
        db.messageDao().insert(MessageEntity(messageId = ByteArray(16).also(rng::nextBytes), conversationId = cid,
            senderId = peer.id, outgoing = outgoing, sentMs = atMs, receivedMs = now, body = "",
            status = if (missed) MessageStatus.DELIVERED else MessageStatus.READ,
            kind = MessageKind.CALL, readReceiptSent = true, callOutcome = outcome.code, callDurationS = durationS))
        if (missed) db.conversationDao().incrementUnread(cid)
        db.conversationDao().touch(cid, atMs)
        cid
    }
}
