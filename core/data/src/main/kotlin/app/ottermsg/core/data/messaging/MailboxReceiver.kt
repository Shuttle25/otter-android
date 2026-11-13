package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.calls.CallInbox
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.media.IncomingAttachments
import app.ottermsg.core.data.media.IncomingPhotos
import app.ottermsg.core.data.push.MessageAlerts
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.entity.SeenEnvelopeEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.NodeApi
import app.ottermsg.proto.v1.Ack
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.ReceiptMessage
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days

/** Turns delivered ENVELOPE records into messages/receipts, then acknowledges them in one ACK record. */
@Singleton
class MailboxReceiver @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val signal: SignalService,
    private val directory: DirectorySync, private val outbox: Outbox, private val clock: Clock,
    private val groups: GroupReceiver, private val photos: IncomingPhotos, private val resets: SessionResets,
    private val alerts: MessageAlerts = MessageAlerts.NONE,
    private val calls: CallInbox = CallInbox.NONE,
) {
    private val seen = db.seenEnvelopeDao()
    private val contacts = db.contactDao()
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val mutex = Mutex()

    suspend fun handle(api: NodeApi, records: List<Record>) = mutex.withLock {
        val me = accounts.require()
        groups.expirePending()
        val acks = mutableListOf<ByteArray>()
        var directorySynced = false
        for (rec in records) {
            val body = runCatching { RecordBody.parseFrom(rec.body) }.getOrNull() ?: continue
            if (body.type != RecordType.ENVELOPE) continue
            val key = body.author.toByteArray() + body.id.toByteArray()
            if (seen.insert(SeenEnvelopeEntity(key, clock.now() + SEEN_TTL_MS)) == -1L) { acks += key; continue }
            var sender = contacts.get(body.author.toByteArray())
            if (sender == null && !directorySynced) {
                directorySynced = true
                runCatching { directory.sync(api) }.onFailure { if (it is CancellationException) throw it }
                sender = contacts.get(body.author.toByteArray())
            }
            if (sender == null) { seen.delete(key); continue } // keep it on the node; retry on a later delivery
            if (!RecordCodec.verify(rec, sender.accountPub)) { acks += key; continue }
            val env = runCatching { Envelope.parseFrom(body.payload) }.getOrNull()
            val mine = env?.recipientsList?.firstOrNull { it.user.toByteArray().contentEquals(me.userId) && it.device == 1 }
            if (env == null || mine == null) { acks += key; continue }
            if (env.kind == Envelope.Kind.SENDER_KEY) {
                groups.onEnvelope(sender.userId, key, rec, body, env) // shown, parked or dropped: the node copy is not needed
                acks += key
                continue
            }
            val plain = try {
                signal.withSessions { _, s -> s.decrypt(s.address(sender.userId), env.kind, mine.content.toByteArray()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val content = plain?.let { runCatching { Content.parseFrom(it) }.getOrNull() }
            // Group control content touches libsignal state and may sync the directory; call content drives the call
            // layer (D78): outside any DB transaction. Call content never creates a chat.
            when (content?.bodyCase) {
                Content.BodyCase.CALL -> {
                    calls.onCallMessage(sender.userId, sender.displayName, content.call, body.createdMs)
                    acks += key; continue
                }
                Content.BodyCase.RECEIPT -> if (calls.isCallReceipt(content.receipt)) {
                    calls.onCallReceipt(sender.userId, content.receipt); acks += key; continue
                }
                Content.BodyCase.GROUP_UPDATE -> { groups.onGroupUpdate(sender.userId, content.groupUpdate.state, api); acks += key; continue }
                Content.BodyCase.SENDER_KEY -> { groups.onSenderKey(sender.userId, content.senderKey); acks += key; continue }
                Content.BodyCase.SESSION_RESET -> { resets.onReceived(sender.userId); acks += key; continue }
                else -> {}
            }
            val shown = db.withTransaction {
                // A receipt never creates a chat (group members we never wrote to send receipts too).
                val cid = if (content?.bodyCase == Content.BodyCase.RECEIPT) conversations.byPeer(sender.userId)?.id ?: -1L
                    else conversations.getOrCreateDirect(sender.userId, sender.displayName, clock.now())
                if (content == null) {
                    messages.insert(MessageEntity(messageId = key.copyOf(16), conversationId = cid, senderId = sender.userId, outgoing = false,
                        sentMs = body.createdMs, receivedMs = clock.now(), body = "", status = MessageStatus.DELIVERED,
                        kind = MessageKind.SYSTEM_UNDECRYPTABLE))
                    conversations.touch(cid, clock.now())
                    null
                } else {
                    cid.takeIf { handleContent(cid, sender, content) }
                }
            }
            if (shown != null) alerts.onIncoming(shown) // after the commit: the notifier reads the stored messages
            if (content == null) resets.onUndecryptable(sender.userId) // throttled (D53)
            acks += key
        }
        if (acks.isNotEmpty()) {
            val ack = Ack.newBuilder().addAllEnvelopeIds(acks.map(::bs)).setDeviceId(1).build()
            api.ack(RecordCodec.sign(RecordCodec.body(RecordType.ACK, me.userId, ack.toByteArray(), createdMs = clock.now()), me.accountKey))
        }
        outbox.kick()
        photos.kick()
    }

    /** Stores [content]; true when it added a visible incoming message (text or photo). */
    private suspend fun handleContent(cid: Long, sender: ContactEntity, content: Content): Boolean {
        when (content.bodyCase) {
            Content.BodyCase.DATA -> {
                val d = content.data
                val sent = if (content.sentMs > 0) content.sentMs else clock.now()
                val photo = IncomingAttachments.photoOrNull(d)
                val id = messages.insert(MessageEntity(messageId = d.messageId.toByteArray(), conversationId = cid, senderId = sender.userId,
                    outgoing = false, sentMs = sent, receivedMs = clock.now(), body = d.text, status = MessageStatus.DELIVERED,
                    kind = if (photo != null) MessageKind.PHOTO else MessageKind.TEXT))
                if (id != -1L) {
                    if (photo != null) photos.store(id, photo)
                    conversations.incrementUnread(cid)
                    conversations.touch(cid, sent)
                    val receipt = Content.newBuilder().setReceipt(ReceiptMessage.newBuilder()
                        .setType(ReceiptMessage.Type.DELIVERED).addMessageIds(d.messageId)).setSentMs(clock.now()).build()
                    outbox.enqueue(sender.userId, receipt, messageLocalId = null, urgent = false)
                }
                return id != -1L
            }
            Content.BodyCase.RECEIPT -> {
                val status = if (content.receipt.type == ReceiptMessage.Type.READ) MessageStatus.READ else MessageStatus.DELIVERED
                content.receipt.messageIdsList.forEach { id ->
                    if (groups.onGroupReceipt(sender.userId, id.toByteArray(), content.receipt.type)) return@forEach
                    messages.advanceStatusIn(cid, id.toByteArray(), status)
                    // delivered or read: no 24 h resend needed any more
                    messages.byMessageId(id.toByteArray())?.takeIf { it.outgoing && it.conversationId == cid }
                        ?.let { db.outboxDao().deleteForMessage(it.localId) }
                }
            }
            else -> {}
        }
        return false
    }

    companion object { val SEEN_TTL_MS = 30.days.inWholeMilliseconds }
}
