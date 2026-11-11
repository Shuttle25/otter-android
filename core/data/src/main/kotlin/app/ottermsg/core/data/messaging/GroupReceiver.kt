package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.group.GroupApplier
import app.ottermsg.core.data.media.IncomingAttachments
import app.ottermsg.core.data.media.IncomingPhotos
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.PendingGroupEnvelopeEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.NodeApi
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.GroupEvent
import app.ottermsg.proto.v1.ReceiptMessage
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.SenderKeyDistribution
import app.ottermsg.proto.v1.SignedGroupState
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.NoSessionException
import java.security.SecureRandom
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days

/**
 * Group side of the receiver (called by [MailboxReceiver] under its lock, never inside a DB transaction because libsignal
 * writes its own state): SENDER_KEY envelopes, sender-key distributions, group updates, and the pending queue of group
 * messages that cannot be shown yet (missing sender key, unknown group, sender not yet a member in our state).
 */
@Singleton
class GroupReceiver @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val signal: SignalService,
    private val applier: GroupApplier, private val outbox: Outbox, private val clock: Clock,
    private val photos: IncomingPhotos,
) {
    private val groups = db.groupDao()
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val recipients = db.messageRecipientDao()
    private val rng = SecureRandom()

    /** A verified SENDER_KEY envelope from [sender]; the caller acks it whatever happens here. */
    suspend fun onEnvelope(sender: ByteArray, recordKey: ByteArray, rec: Record, body: RecordBody, env: Envelope) {
        when (val r = decrypt(sender, env)) {
            is Decrypted.Ok -> place(sender, recordKey, r.content, body.createdMs, receivedMs = clock.now())
            Decrypted.NoKey -> groups.insertPendingEnvelope(PendingGroupEnvelopeEntity(recordKey, null, sender, rec.toByteArray(),
                clock.now(), decrypted = false, sentMs = body.createdMs))
            Decrypted.Drop -> {}
        }
    }

    /** A 1:1 SenderKeyDistribution: store the sender's key, then retry what was waiting for it. */
    suspend fun onSenderKey(sender: ByteArray, skd: SenderKeyDistribution) {
        val ok = try {
            signal.withGroups { g -> g.process(g.address(sender), skd.message.toByteArray()) }; true
        } catch (e: CancellationException) { throw e } catch (e: Exception) { log.warning("bad sender key from a member: $e"); false }
        if (ok) retry(groups.pendingEnvelopesFrom(sender))
    }

    /** A 1:1 GroupUpdate: apply it, then retry group messages waiting for the accepted state(s). */
    suspend fun onGroupUpdate(from: ByteArray, signed: SignedGroupState, api: NodeApi?) {
        val r = applier.apply(signed, from, api)
        for (gid in r.accepted) retry(groups.pendingEnvelopesFor(gid))
    }

    /**
     * A receipt from [author] for one of our messages in a group: updates that recipient's row only if [author] is in the
     * frozen set (D23), then the aggregate. Returns false if the message is not an outgoing group message.
     */
    suspend fun onGroupReceipt(author: ByteArray, messageId: ByteArray, type: ReceiptMessage.Type): Boolean {
        val m = messages.byMessageId(messageId)?.takeIf { it.outgoing } ?: return false
        if (conversations.get(m.conversationId)?.type != ConversationType.GROUP) return false
        db.withTransaction {
            val now = clock.now()
            if (type == ReceiptMessage.Type.READ) recipients.setRead(m.localId, author, now) else recipients.setDelivered(m.localId, author, now)
            val c = recipients.counts(m.localId)
            if (c.total > 0 && c.read == c.total) messages.advanceStatusLocal(m.localId, MessageStatus.READ)
            else if (c.total > 0 && c.delivered == c.total) messages.advanceStatusLocal(m.localId, MessageStatus.DELIVERED)
            if (c.total > 0 && c.delivered == c.total) db.outboxDao().deleteForMessage(m.localId) // no 24 h resend needed
        }
        return true
    }

    /** Pending entries older than 7 days become one "undecryptable" system message each. */
    suspend fun expirePending() {
        for (p in groups.pendingEnvelopesBefore(clock.now() - PENDING_SHOW_AFTER_MS)) {
            db.withTransaction {
                val cid = p.groupId?.let { conversations.byGroup(it)?.id } ?: guessConversation(p.sender)
                val event = GroupEvent.newBuilder().setType(GroupEvent.Type.UNDECRYPTABLE).setActor(bs(p.sender)).build()
                messages.insert(MessageEntity(messageId = ByteArray(16).also(rng::nextBytes), conversationId = cid, senderId = p.sender,
                    outgoing = false, sentMs = if (p.sentMs > 0) p.sentMs else p.receivedMs, receivedMs = clock.now(), body = "",
                    status = MessageStatus.DELIVERED, kind = MessageKind.SYSTEM_GROUP, systemData = event.toByteArray()))
                conversations.touch(cid, clock.now())
                groups.deletePendingEnvelope(p.recordKey)
            }
        }
    }

    /** Best guess for a message we never could decrypt: the only group (in our state) with that sender, else the direct chat. */
    private suspend fun guessConversation(sender: ByteArray): Long {
        val candidates = groups.groupsWithMember(sender)
        if (candidates.size == 1) conversations.byGroup(candidates[0])?.let { return it.id }
        val name = db.contactDao().get(sender)?.displayName ?: ""
        return conversations.getOrCreateDirect(sender, name, clock.now())
    }

    private suspend fun retry(pending: List<PendingGroupEnvelopeEntity>) {
        for (p in pending) {
            if (p.decrypted) {
                val content = runCatching { Content.parseFrom(p.record) }.getOrNull()
                groups.deletePendingEnvelope(p.recordKey)
                if (content != null) place(p.sender, p.recordKey, content, p.sentMs, p.receivedMs)
                continue
            }
            val rec = runCatching { Record.parseFrom(p.record) }.getOrNull()
            val body = rec?.let { runCatching { RecordBody.parseFrom(it.body) }.getOrNull() }
            val env = body?.let { runCatching { Envelope.parseFrom(it.payload) }.getOrNull() }
            if (env == null) { groups.deletePendingEnvelope(p.recordKey); continue }
            when (val r = decrypt(p.sender, env)) {
                is Decrypted.Ok -> { groups.deletePendingEnvelope(p.recordKey); place(p.sender, p.recordKey, r.content, body.createdMs, p.receivedMs) }
                Decrypted.NoKey -> {}
                Decrypted.Drop -> groups.deletePendingEnvelope(p.recordKey)
            }
        }
    }

    private sealed interface Decrypted {
        class Ok(val content: Content) : Decrypted
        data object NoKey : Decrypted
        data object Drop : Decrypted
    }

    private suspend fun decrypt(sender: ByteArray, env: Envelope): Decrypted {
        val plain = try {
            signal.withGroups { g -> g.decrypt(g.address(sender), env.sharedContent.toByteArray()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoSessionException) {
            return Decrypted.NoKey
        } catch (e: DuplicateMessageException) {
            return Decrypted.Drop
        } catch (e: Exception) {
            log.warning("group message from a member could not be decrypted: $e")
            return Decrypted.Drop
        }
        return runCatching { Content.parseFrom(plain) }.getOrNull()?.let { Decrypted.Ok(it) } ?: Decrypted.Drop
    }

    /**
     * Shows a decrypted group message, or parks it (decrypted) until our state makes the sender a member.
     * Dropped when it is not a group text, or when we are no longer a member.
     */
    private suspend fun place(sender: ByteArray, recordKey: ByteArray, content: Content, envelopeSentMs: Long, receivedMs: Long) {
        if (content.bodyCase != Content.BodyCase.DATA || !content.data.hasGroup()) return
        val d = content.data
        val gid = d.group.groupId.toByteArray()
        val me = accounts.require().userId
        val g = groups.get(gid)
        val senderIsMember = g != null && groups.isMember(gid, sender) > 0
        if (g == null || (g.iAmMember && !senderIsMember)) {
            groups.upsertPendingEnvelope(PendingGroupEnvelopeEntity(recordKey, gid, sender, content.toByteArray(), receivedMs,
                decrypted = true, sentMs = if (content.sentMs > 0) content.sentMs else envelopeSentMs))
            return
        }
        if (!g.iAmMember || groups.isMember(gid, me) == 0) { log.info("group message dropped: not a member"); return }
        val inserted = db.withTransaction {
            val cid = conversations.getOrCreateGroupConversation(gid, g.name, clock.now())
            val sent = if (content.sentMs > 0) content.sentMs else clock.now()
            val photo = IncomingAttachments.photoOrNull(d)
            val id = messages.insert(MessageEntity(messageId = d.messageId.toByteArray(), conversationId = cid, senderId = sender,
                outgoing = false, sentMs = sent, receivedMs = clock.now(), body = d.text, status = MessageStatus.DELIVERED,
                kind = if (photo != null) MessageKind.PHOTO else MessageKind.TEXT))
            if (id != -1L) {
                if (photo != null) photos.store(id, photo)
                conversations.incrementUnread(cid); conversations.touch(cid, sent)
            }
            id != -1L
        }
        if (inserted) {
            photos.kick()
            val receipt = Content.newBuilder().setReceipt(ReceiptMessage.newBuilder().setType(ReceiptMessage.Type.DELIVERED)
                .addMessageIds(d.messageId)).setSentMs(clock.now()).build()
            outbox.enqueue(sender, receipt, messageLocalId = null, urgent = false)
        }
    }

    companion object {
        val PENDING_SHOW_AFTER_MS = 7.days.inWholeMilliseconds
        private val log = Logger.getLogger("GroupReceiver")
    }
}
