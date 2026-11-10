package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.OutboxEntity
import app.ottermsg.core.database.model.ConversationSummary
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.DataMessage
import app.ottermsg.proto.v1.ReceiptMessage
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val outbox: Outbox, private val clock: Clock,
) {
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val contacts = db.contactDao()
    private val rng = SecureRandom()

    fun observeSummaries(): Flow<List<ConversationSummary>> = conversations.observeSummaries()
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = messages.observe(conversationId)
    fun observeConversation(conversationId: Long): Flow<ConversationEntity?> = conversations.observe(conversationId)

    /** The direct conversation with a family member (created on first use). */
    suspend fun conversationWith(userId: ByteArray): Long {
        val title = contacts.get(userId)?.displayName ?: ""
        return conversations.getOrCreateDirect(userId, title, clock.now())
    }

    /** Stores the message and its outbox item atomically; returns the local id, or null for blank text. */
    suspend fun sendText(conversationId: Long, text: String): Long? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val me = accounts.require()
        val now = clock.now()
        val messageId = ByteArray(16).also(rng::nextBytes)
        val localId = db.withTransaction {
            val conv = checkNotNull(conversations.get(conversationId)) { "no conversation $conversationId" }
            val localId = messages.insert(MessageEntity(messageId = messageId, conversationId = conversationId, senderId = me.userId,
                outgoing = true, sentMs = now, receivedMs = now, body = body, status = MessageStatus.PENDING))
            db.outboxDao().insert(OutboxEntity(recipientUserId = conv.peerUserId,
                contentBytes = dataContent(messageId, body, now).toByteArray(), messageLocalId = localId, urgent = true, createdMs = now))
            conversations.setDraft(conversationId, "")
            conversations.touch(conversationId, now)
            localId
        }
        outbox.kick()
        return localId
    }

    /** Re-queues a FAILED outgoing message with the same message_id. */
    suspend fun retry(localId: Long): Boolean {
        val now = clock.now()
        val ok = db.withTransaction {
            val m = messages.get(localId) ?: return@withTransaction false
            if (!m.outgoing || m.status != MessageStatus.FAILED || m.kind != MessageKind.TEXT) return@withTransaction false
            val conv = conversations.get(m.conversationId) ?: return@withTransaction false
            db.outboxDao().insert(OutboxEntity(recipientUserId = conv.peerUserId,
                contentBytes = dataContent(m.messageId, m.body, m.sentMs).toByteArray(), messageLocalId = localId, urgent = true, createdMs = now))
            messages.resetFailed(localId)
            true
        }
        if (ok) outbox.kick()
        return ok
    }

    suspend fun deleteLocal(localId: Long) = messages.delete(localId)
    suspend fun setDraft(conversationId: Long, draft: String) = conversations.setDraft(conversationId, draft)

    /** Clears unread and queues one READ receipt for incoming messages not yet reported as read. */
    suspend fun markConversationRead(conversationId: Long) {
        val queued = db.withTransaction {
            conversations.clearUnread(conversationId)
            val conv = conversations.get(conversationId) ?: return@withTransaction false
            val pending = messages.unreadReceiptsPending(conversationId)
            if (pending.isEmpty()) return@withTransaction false
            val receipt = Content.newBuilder().setReceipt(ReceiptMessage.newBuilder().setType(ReceiptMessage.Type.READ)
                .addAllMessageIds(pending.map { bs(it.messageId) })).setSentMs(clock.now()).build()
            db.outboxDao().insert(OutboxEntity(recipientUserId = conv.peerUserId,
                contentBytes = receipt.toByteArray(), messageLocalId = null, urgent = false, createdMs = clock.now()))
            messages.markReadReceiptSent(pending.map { it.localId })
            true
        }
        if (queued) outbox.kick()
    }

    private fun dataContent(messageId: ByteArray, text: String, sentMs: Long): Content =
        Content.newBuilder().setData(DataMessage.newBuilder().setMessageId(bs(messageId)).setText(text)).setSentMs(sentMs).build()
}
