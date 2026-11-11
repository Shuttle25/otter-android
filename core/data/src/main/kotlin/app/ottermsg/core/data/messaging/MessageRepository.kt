package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.MessageRecipientEntity
import app.ottermsg.core.database.entity.OutboxEntity
import app.ottermsg.core.database.model.ConversationSummary
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.DataMessage
import app.ottermsg.proto.v1.GroupContext
import app.ottermsg.proto.v1.ReceiptMessage
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** What a conversation screen needs (seam for ViewModel tests). */
interface ConversationStore {
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>
    fun observeConversation(conversationId: Long): Flow<ConversationEntity?>
    suspend fun sendText(conversationId: Long, text: String): Long?
    suspend fun retry(localId: Long): Boolean
    suspend fun deleteLocal(localId: Long)
    suspend fun setDraft(conversationId: Long, draft: String)
    suspend fun markConversationRead(conversationId: Long)
}

@Singleton
class MessageRepository @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val outbox: Outbox, private val clock: Clock,
) : ConversationStore {
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val contacts = db.contactDao()
    private val rng = SecureRandom()

    fun observeSummaries(): Flow<List<ConversationSummary>> = conversations.observeSummaries()
    override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = messages.observe(conversationId)
    override fun observeConversation(conversationId: Long): Flow<ConversationEntity?> = conversations.observe(conversationId)

    /** The direct conversation with a family member (created on first use). */
    suspend fun conversationWith(userId: ByteArray): Long {
        val title = contacts.get(userId)?.displayName ?: ""
        return conversations.getOrCreateDirect(userId, title, clock.now())
    }

    /** Stores the message and its outbox item atomically; returns the local id, or null for blank text. */
    override suspend fun sendText(conversationId: Long, text: String): Long? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val me = accounts.require()
        val now = clock.now()
        val messageId = ByteArray(16).also(rng::nextBytes)
        val localId = db.withTransaction {
            val conv = checkNotNull(conversations.get(conversationId)) { "no conversation $conversationId" }
            val gid = conv.groupId
            val recipients = if (gid != null) {
                val g = db.groupDao().get(gid)
                if (g == null || !g.iAmMember) return@withTransaction null // read-only: no longer a member
                db.groupDao().members(gid).map { it.userId }.filterNot { it.contentEquals(me.userId) }
            } else null
            val localId = messages.insert(MessageEntity(messageId = messageId, conversationId = conversationId, senderId = me.userId,
                outgoing = true, sentMs = now, receivedMs = now, body = body, status = MessageStatus.PENDING))
            if (gid != null) {
                // D23: the recipient set is frozen now (current members except me); one outbox item, one envelope.
                db.messageRecipientDao().insertAll(recipients.orEmpty().map { MessageRecipientEntity(localId, it) })
                db.outboxDao().insert(OutboxEntity(recipientUserId = ByteArray(0), groupId = gid,
                    contentBytes = dataContent(messageId, body, now, gid).toByteArray(), messageLocalId = localId, urgent = true, createdMs = now))
            } else {
                db.outboxDao().insert(OutboxEntity(recipientUserId = checkNotNull(conv.peerUserId),
                    contentBytes = dataContent(messageId, body, now).toByteArray(), messageLocalId = localId, urgent = true, createdMs = now))
            }
            conversations.setDraft(conversationId, "")
            conversations.touch(conversationId, now)
            localId
        } ?: return null
        outbox.kick()
        return localId
    }

    /** Re-queues a FAILED outgoing message with the same message_id. */
    override suspend fun retry(localId: Long): Boolean {
        val now = clock.now()
        val ok = db.withTransaction {
            val m = messages.get(localId) ?: return@withTransaction false
            if (!m.outgoing || m.status != MessageStatus.FAILED || m.kind != MessageKind.TEXT) return@withTransaction false
            val conv = conversations.get(m.conversationId) ?: return@withTransaction false
            val gid = conv.groupId
            if (gid != null) {
                if (db.groupDao().get(gid)?.iAmMember != true) return@withTransaction false
                db.outboxDao().insert(OutboxEntity(recipientUserId = ByteArray(0), groupId = gid,
                    contentBytes = dataContent(m.messageId, m.body, m.sentMs, gid).toByteArray(), messageLocalId = localId, urgent = true, createdMs = now))
            } else {
                db.outboxDao().insert(OutboxEntity(recipientUserId = checkNotNull(conv.peerUserId),
                    contentBytes = dataContent(m.messageId, m.body, m.sentMs).toByteArray(), messageLocalId = localId, urgent = true, createdMs = now))
            }
            messages.resetFailed(localId)
            true
        }
        if (ok) outbox.kick()
        return ok
    }

    override suspend fun deleteLocal(localId: Long) { messages.delete(localId) }
    override suspend fun setDraft(conversationId: Long, draft: String) { conversations.setDraft(conversationId, draft) }

    /** Clears unread and queues one READ receipt for incoming messages not yet reported as read. */
    override suspend fun markConversationRead(conversationId: Long) {
        val queued = db.withTransaction {
            conversations.clearUnread(conversationId)
            val conv = conversations.get(conversationId) ?: return@withTransaction false
            val pending = messages.unreadReceiptsPending(conversationId)
            if (pending.isEmpty()) return@withTransaction false
            // One READ receipt per author (in a group: one per sender), always 1:1 to that author.
            for ((_, ms) in pending.groupBy { it.senderId.toList() }) {
                val receipt = Content.newBuilder().setReceipt(ReceiptMessage.newBuilder().setType(ReceiptMessage.Type.READ)
                    .addAllMessageIds(ms.map { bs(it.messageId) })).setSentMs(clock.now()).build()
                db.outboxDao().insert(OutboxEntity(recipientUserId = if (conv.groupId != null) ms[0].senderId else checkNotNull(conv.peerUserId),
                    contentBytes = receipt.toByteArray(), messageLocalId = null, urgent = false, createdMs = clock.now()))
            }
            messages.markReadReceiptSent(pending.map { it.localId })
            true
        }
        if (queued) outbox.kick()
    }

    private fun dataContent(messageId: ByteArray, text: String, sentMs: Long, groupId: ByteArray? = null): Content =
        Content.newBuilder().setData(DataMessage.newBuilder().setMessageId(bs(messageId)).setText(text)
            .apply { if (groupId != null) setGroup(GroupContext.newBuilder().setGroupId(bs(groupId))) }).setSentMs(sentMs).build()
}
