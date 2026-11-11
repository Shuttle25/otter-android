package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.directory.DirectorySync
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
) {
    private val seen = db.seenEnvelopeDao()
    private val contacts = db.contactDao()
    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val mutex = Mutex()

    suspend fun handle(api: NodeApi, records: List<Record>) = mutex.withLock {
        val me = accounts.require()
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
            val plain = try {
                signal.withSessions { _, s -> s.decrypt(s.address(sender.userId), env.kind, mine.content.toByteArray()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            db.withTransaction {
                val cid = conversations.getOrCreateDirect(sender.userId, sender.displayName, clock.now())
                val content = plain?.let { runCatching { Content.parseFrom(it) }.getOrNull() }
                if (content == null) {
                    messages.insert(MessageEntity(messageId = key.copyOf(16), conversationId = cid, senderId = sender.userId, outgoing = false,
                        sentMs = body.createdMs, receivedMs = clock.now(), body = "", status = MessageStatus.DELIVERED,
                        kind = MessageKind.SYSTEM_UNDECRYPTABLE))
                    conversations.touch(cid, clock.now())
                } else {
                    handleContent(cid, sender, content)
                }
            }
            acks += key
        }
        if (acks.isNotEmpty()) {
            val ack = Ack.newBuilder().addAllEnvelopeIds(acks.map(::bs)).setDeviceId(1).build()
            api.ack(RecordCodec.sign(RecordCodec.body(RecordType.ACK, me.userId, ack.toByteArray(), createdMs = clock.now()), me.accountKey))
        }
        outbox.kick()
    }

    private suspend fun handleContent(cid: Long, sender: ContactEntity, content: Content) {
        when (content.bodyCase) {
            Content.BodyCase.DATA -> {
                val d = content.data
                val sent = if (content.sentMs > 0) content.sentMs else clock.now()
                val id = messages.insert(MessageEntity(messageId = d.messageId.toByteArray(), conversationId = cid, senderId = sender.userId,
                    outgoing = false, sentMs = sent, receivedMs = clock.now(), body = d.text, status = MessageStatus.DELIVERED))
                if (id != -1L) {
                    conversations.incrementUnread(cid)
                    conversations.touch(cid, sent)
                    val receipt = Content.newBuilder().setReceipt(ReceiptMessage.newBuilder()
                        .setType(ReceiptMessage.Type.DELIVERED).addMessageIds(d.messageId)).setSentMs(clock.now()).build()
                    outbox.enqueue(sender.userId, receipt, messageLocalId = null, urgent = false)
                }
            }
            Content.BodyCase.RECEIPT -> {
                val status = if (content.receipt.type == ReceiptMessage.Type.READ) MessageStatus.READ else MessageStatus.DELIVERED
                content.receipt.messageIdsList.forEach { id ->
                    messages.advanceStatusIn(cid, id.toByteArray(), status)
                    // delivered or read: no 24 h resend needed any more
                    messages.byMessageId(id.toByteArray())?.takeIf { it.outgoing && it.conversationId == cid }
                        ?.let { db.outboxDao().deleteForMessage(it.localId) }
                }
            }
            else -> {}
        }
    }

    companion object { val SEEN_TTL_MS = 30.days.inWholeMilliseconds }
}
