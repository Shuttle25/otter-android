package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.OutboxEntity
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.Recipient
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

class PermanentFailure(msg: String) : Exception(msg)

/** Persistent queue of encrypted 1:1 sends. Processed serially while a session is online. */
@Singleton
class Outbox @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val signal: SignalService, private val clock: Clock,
) {
    private val outbox = db.outboxDao()
    private val messages = db.messageDao()
    private val contacts = db.contactDao()
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()

    fun kick() { kicks.trySend(Unit) }

    /** Queues [content] for [recipient] and wakes the worker. */
    suspend fun enqueue(recipient: ByteArray, content: Content, messageLocalId: Long?, urgent: Boolean): Long =
        outbox.insert(OutboxEntity(recipientUserId = recipient, contentBytes = content.toByteArray(), messageLocalId = messageLocalId,
            urgent = urgent, createdMs = clock.now())).also { kick() }

    /** Worker for one online session; returns only by cancellation (the session ended). */
    suspend fun run(api: NodeApi): Nothing {
        while (true) {
            drainOnce(api)
            val next = outbox.nextAttemptAt()
            if (next == null) kicks.receive()
            else withTimeoutOrNull((next - clock.now()).coerceAtLeast(1)) { kicks.receive() }
        }
    }

    /** Processes every due item once, in order. */
    suspend fun drainOnce(api: NodeApi) = mutex.withLock {
        val me = accounts.require()
        outbox.deleteSentBefore(clock.now() - KEEP_SENT_MS)
        for (item in outbox.due(clock.now())) {
            try {
                process(api, item, me)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (item.sentMs != null) { // the one 24 h resend failed: the message itself was delivered to a node before
                    outbox.markResent(item.id)
                    continue
                }
                val permanent = e is PermanentFailure || (e is RpcException &&
                    e.status in setOf(Status.STATUS_INVALID, Status.STATUS_NOT_FOUND, Status.STATUS_REVOKED))
                if (permanent) {
                    db.withTransaction {
                        item.messageLocalId?.let { messages.markFailed(it) }
                        outbox.delete(item.id)
                    }
                } else {
                    val delay = minOf((1L shl minOf(item.attempts, 20)) * 1000, MAX_BACKOFF_MS)
                    outbox.reschedule(item.id, item.attempts + 1, clock.now() + delay)
                }
            }
        }
    }

    private suspend fun process(api: NodeApi, item: OutboxEntity, me: LocalAccount) {
        val record = item.recordBytes?.let { Record.parseFrom(it) } ?: run {
            val contact = contacts.get(item.recipientUserId) ?: throw PermanentFailure("unknown contact")
            val (kind, ct) = signal.withSessions { _, s ->
                val addr = s.address(item.recipientUserId)
                if (!s.hasSession(addr)) null else s.encrypt(addr, item.contentBytes)
            } ?: run {
                val bundle = api.fetchPrekeys(item.recipientUserId)
                signal.withSessions { _, s ->
                    s.processBundle(bundle, contact.identityPub)
                    s.encrypt(s.address(item.recipientUserId), item.contentBytes)
                }
            }
            val env = Envelope.newBuilder().setSenderUser(bs(me.userId)).setSenderDevice(1).setKind(kind).setUrgent(item.urgent)
                .addRecipients(Recipient.newBuilder().setUser(bs(item.recipientUserId)).setDevice(1).setContent(bs(ct))).build()
            // Persist before the first send so retries resend the identical record (the session already advanced).
            RecordCodec.sign(RecordCodec.body(RecordType.ENVELOPE, me.userId, env.toByteArray(), createdMs = clock.now()), me.accountKey)
                .also { outbox.setRecord(item.id, it.toByteArray()) }
        }
        api.send(record) // the same record every time: nodes deduplicate by author‖id
        val localId = item.messageLocalId
        db.withTransaction {
            when {
                item.sentMs != null -> outbox.markResent(item.id) // 24 h without a receipt: re-sent once
                localId != null -> { // data message: keep until a receipt arrives (or 7 days)
                    outbox.markSent(item.id, clock.now())
                    messages.advanceStatusLocal(localId, MessageStatus.SENT)
                }
                else -> outbox.delete(item.id) // receipts are fire-and-forget
            }
        }
    }

    companion object {
        const val MAX_BACKOFF_MS = 300_000L
        const val KEEP_SENT_MS = 7 * 86_400_000L
    }
}
