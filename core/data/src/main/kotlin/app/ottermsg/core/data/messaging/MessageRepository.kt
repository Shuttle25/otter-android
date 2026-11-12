package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.data.Clock
import app.ottermsg.core.crypto.BlobCipher
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.media.BlobMetaBuilder
import app.ottermsg.core.data.media.MediaFiles
import app.ottermsg.core.data.media.MediaTransfers
import app.ottermsg.core.data.media.PhotoPrep
import app.ottermsg.core.data.push.MessageAlerts
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState
import app.ottermsg.proto.v1.Record
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import app.ottermsg.core.data.bs
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.dao.RecipientStatus
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** What a conversation screen needs (seam for ViewModel tests). */
interface ConversationStore {
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>
    fun observeConversation(conversationId: Long): Flow<ConversationEntity?>
    suspend fun sendText(conversationId: Long, text: String): Long?
    /**
     * Prepares, encrypts and queues one photo with a caption (D47/D48). Deletes [source] afterwards. Returns null when
     * the image can't be read (or the group is read-only).
     */
    suspend fun sendPhoto(conversationId: Long, source: java.io.File, caption: String): Long? = null
    suspend fun retry(localId: Long): Boolean
    suspend fun deleteLocal(localId: Long)
    suspend fun setDraft(conversationId: Long, draft: String)
    suspend fun markConversationRead(conversationId: Long)
    /** Group conversations: the group and its members (null for direct chats). */
    fun observeGroup(conversationId: Long): Flow<GroupView?> = flowOf(null)
    fun observeContacts(): Flow<List<ContactEntity>> = flowOf(emptyList())
    suspend fun myId(): ByteArray = ByteArray(0)
    /** Message info (D23): the frozen recipients of an outgoing group message with their receipt times. */
    fun observeRecipients(localId: Long): Flow<List<RecipientStatus>> = flowOf(emptyList())
    fun observeMessage(localId: Long): Flow<MessageEntity?> = flowOf(null)
}

class GroupView(val group: GroupEntity, val members: List<GroupMemberEntity>)

@Singleton
class MessageRepository @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val outbox: Outbox, private val clock: Clock,
    private val files: MediaFiles, private val prep: PhotoPrep, private val media: MediaTransfers,
    private val alerts: MessageAlerts = MessageAlerts.NONE,
) : ConversationStore {
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val contacts = db.contactDao()
    private val rng = SecureRandom()

    fun observeSummaries(): Flow<List<ConversationSummary>> = conversations.observeSummaries()
    override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = messages.observe(conversationId)
    override fun observeConversation(conversationId: Long): Flow<ConversationEntity?> = conversations.observe(conversationId)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGroup(conversationId: Long): Flow<GroupView?> = conversations.observe(conversationId).flatMapLatest { c ->
        val gid = c?.groupId ?: return@flatMapLatest flowOf(null)
        combine(db.groupDao().observe(gid), db.groupDao().observeMembers(gid)) { g, ms -> g?.let { GroupView(it, ms) } }
    }
    override fun observeContacts(): Flow<List<ContactEntity>> = contacts.observeAll()
    override suspend fun myId(): ByteArray = accounts.require().userId
    override fun observeRecipients(localId: Long): Flow<List<RecipientStatus>> = db.messageRecipientDao().observeRecipients(localId)
    override fun observeMessage(localId: Long): Flow<MessageEntity?> = messages.observeOne(localId)

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

    override suspend fun sendPhoto(conversationId: Long, source: File, caption: String): Long? {
        val me = accounts.require()
        val conv = conversations.get(conversationId) ?: return null
        val gid = conv.groupId
        val readers = if (gid != null) {
            val g = db.groupDao().get(gid)
            if (g == null || !g.iAmMember) return null
            db.groupDao().members(gid).map { it.userId }.filterNot { it.contentEquals(me.userId) }
        } else listOf(checkNotNull(conv.peerUserId))
        if (readers.isEmpty()) return null
        val now = clock.now()
        val name = files.newName()
        val prepared = withContext(Dispatchers.IO) {
            val p = try { prep.prepare(source) } catch (e: PhotoPrep.Unreadable) { return@withContext null }
            val id = ByteArray(16).also(rng::nextBytes)
            val blobKey = BlobMetaBuilder.blobKey(me.userId, id)
            val key = BlobCipher.newKey()
            val sealed = BlobCipher.encrypt(p.jpeg.inputStream(), files.file(name), key, blobKey)
            val meta = BlobMetaBuilder.build(id, me, files.file(name), readers, now)
            AttachmentEntity(messageLocalId = 0, blobKey = blobKey, key = key, digest = sealed.sha256, size = sealed.size,
                mime = "image/jpeg", width = p.width, height = p.height, thumb = p.thumb, file = name,
                state = AttachmentState.UPLOADING, metaRecord = meta.toByteArray())
        } ?: return null
        val localId = queuePhoto(conversationId, gid, readers, prepared, caption, now)
        withContext(Dispatchers.IO) { source.delete() }
        media.kick()
        return localId
    }

    /** Stores a photo message whose ciphertext and signed BLOB_META are ready ([prepared]); the upload starts on the next pass. */
    internal suspend fun queuePhoto(conversationId: Long, gid: ByteArray?, readers: List<ByteArray>, prepared: AttachmentEntity,
                                    caption: String, now: Long): Long {
        val me = accounts.require()
        val body = caption.trim()
        val messageId = ByteArray(16).also(rng::nextBytes)
        val localId = db.withTransaction {
            val localId = messages.insert(MessageEntity(messageId = messageId, conversationId = conversationId, senderId = me.userId,
                outgoing = true, sentMs = now, receivedMs = now, body = body, status = MessageStatus.PENDING, kind = MessageKind.PHOTO))
            val a = prepared.copy(messageLocalId = localId)
            db.attachmentDao().insert(a)
            val content = dataContent(messageId, body, now, gid, a).toByteArray()
            if (gid != null) {
                db.messageRecipientDao().insertAll(readers.map { MessageRecipientEntity(localId, it) })
                db.outboxDao().insert(OutboxEntity(recipientUserId = ByteArray(0), groupId = gid, contentBytes = content,
                    messageLocalId = localId, urgent = true, createdMs = now, held = true))
            } else {
                db.outboxDao().insert(OutboxEntity(recipientUserId = readers[0], contentBytes = content,
                    messageLocalId = localId, urgent = true, createdMs = now, held = true))
            }
            conversations.setDraft(conversationId, "")
            conversations.touch(conversationId, now)
            localId
        }
        media.kick()
        return localId
    }

    /** Test seam: queue an already encrypted file (LiveBlobTest's 20 MiB blob). */
    internal suspend fun sendEncryptedFile(conversationId: Long, file: File, key: ByteArray, id: ByteArray, sha256: ByteArray): Long {
        val me = accounts.require()
        val peer = checkNotNull(conversations.get(conversationId)?.peerUserId)
        val now = clock.now()
        val meta = BlobMetaBuilder.build(id, me, file, listOf(peer), now)
        val a = AttachmentEntity(messageLocalId = 0, blobKey = BlobMetaBuilder.blobKey(me.userId, id), key = key, digest = sha256,
            size = file.length(), mime = "image/jpeg", width = 1, height = 1, thumb = null, file = file.name,
            state = AttachmentState.UPLOADING, metaRecord = meta.toByteArray())
        return queuePhoto(conversationId, null, listOf(peer), a, "", now)
    }

    /** Re-queues a FAILED outgoing message with the same message_id (a photo whose upload failed is re-published). */
    override suspend fun retry(localId: Long): Boolean {
        val now = clock.now()
        val me = accounts.require()
        var upload = false
        val ok = db.withTransaction {
            val m = messages.get(localId) ?: return@withTransaction false
            if (!m.outgoing || m.status != MessageStatus.FAILED || (m.kind != MessageKind.TEXT && m.kind != MessageKind.PHOTO)) return@withTransaction false
            var att: AttachmentEntity? = null
            var held = false
            if (m.kind == MessageKind.PHOTO) {
                val a = db.attachmentDao().forMessage(localId) ?: return@withTransaction false
                if (a.state == AttachmentState.UPLOAD_FAILED) {
                    // same record id: the blob key is the ciphertext's aad (D48)
                    val meta = BlobMetaBuilder.resign(Record.parseFrom(checkNotNull(a.metaRecord)), me, now)
                    att = a.copy(state = AttachmentState.UPLOADING, metaRecord = meta.toByteArray(), attempts = 0, nextAttemptMs = 0, doneChunks = 0)
                    db.attachmentDao().update(att)
                    held = true
                    upload = true
                } else att = a
            }
            val conv = conversations.get(m.conversationId) ?: return@withTransaction false
            val gid = conv.groupId
            if (gid != null) {
                if (db.groupDao().get(gid)?.iAmMember != true) return@withTransaction false
                db.outboxDao().insert(OutboxEntity(recipientUserId = ByteArray(0), groupId = gid,
                    contentBytes = dataContent(m.messageId, m.body, m.sentMs, gid, att).toByteArray(), messageLocalId = localId,
                    urgent = true, createdMs = now, held = held))
            } else {
                db.outboxDao().insert(OutboxEntity(recipientUserId = checkNotNull(conv.peerUserId),
                    contentBytes = dataContent(m.messageId, m.body, m.sentMs, null, att).toByteArray(), messageLocalId = localId,
                    urgent = true, createdMs = now, held = held))
            }
            messages.resetFailed(localId)
            true
        }
        if (ok) { outbox.kick(); if (upload) media.kick() }
        return ok
    }

    override suspend fun deleteLocal(localId: Long) {
        val a = db.attachmentDao().forMessage(localId)
        db.withTransaction {
            db.attachmentDao().deleteForMessage(localId)
            // a photo still uploading would leave its held envelope behind forever
            if (a != null && (a.state == AttachmentState.UPLOADING || a.state == AttachmentState.UPLOAD_FAILED))
                db.outboxDao().deleteForMessage(localId)
            messages.delete(localId)
        }
        a?.file?.let { name -> withContext(Dispatchers.IO) { files.file(name).delete(); files.part(name).delete() } }
    }
    override suspend fun setDraft(conversationId: Long, draft: String) { conversations.setDraft(conversationId, draft) }

    /** Clears unread and queues one READ receipt for incoming messages not yet reported as read. */
    override suspend fun markConversationRead(conversationId: Long) {
        alerts.onRead(conversationId)
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

    private fun dataContent(messageId: ByteArray, text: String, sentMs: Long, groupId: ByteArray? = null,
                            attachment: AttachmentEntity? = null): Content =
        Content.newBuilder().setData(DataMessage.newBuilder().setMessageId(bs(messageId)).setText(text)
            .apply { if (groupId != null) setGroup(GroupContext.newBuilder().setGroupId(bs(groupId))) }
            .apply { if (attachment != null) addAttachments(BlobMetaBuilder.attachment(attachment)) }).setSentMs(sentMs).build()
}
