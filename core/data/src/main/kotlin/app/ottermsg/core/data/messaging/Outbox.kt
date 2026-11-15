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

/**
 * Persistent queue of encrypted sends: 1:1 items and group items (one SENDER_KEY envelope each). Processed serially
 * while a session is online.
 */
@Singleton
class Outbox @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val signal: SignalService, private val clock: Clock,
    private val groupSender: GroupSender,
) {
    private val outbox = db.outboxDao()
    private val messages = db.messageDao()
    private val contacts = db.contactDao()
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()

    fun kick() { kicks.trySend(Unit) }

    /**
     * Queues [content] for [recipient] and wakes the worker. [expiresMs] > 0 (call signaling, D78): the record carries
     * that expiry and the item is dropped unsent once it has passed.
     */
    suspend fun enqueue(recipient: ByteArray, content: Content, messageLocalId: Long?, urgent: Boolean, expiresMs: Long = 0): Long =
        outbox.insert(OutboxEntity(recipientUserId = recipient, contentBytes = content.toByteArray(), messageLocalId = messageLocalId,
            urgent = urgent, createdMs = clock.now(), expiresMs = expiresMs)).also { kick() }

    /** Worker for one online session; returns only by cancellation (the session ended). */
    suspend fun run(api: NodeApi): Nothing {
        while (true) {
            drainOnce(api)
            val next = outbox.nextAttemptAt()
            if (next == null) kicks.receive()
            else withTimeoutOrNull((next - clock.now()).coerceAtLeast(1)) { kicks.receive() }
        }
    }

    /** Something is due now (BackgroundSync keeps the engine up until it is sent). */
    suspend fun hasDue(): Boolean = outbox.due(clock.now(), 1).isNotEmpty()

    /** Processes every due item once, in order. */
    suspend fun drainOnce(api: NodeApi) = mutex.withLock {
        val me = accounts.require()
        outbox.deleteSentBefore(clock.now() - KEEP_SENT_MS)
        for (item in outbox.due(clock.now())) attempt(api, item, me)
    }

    /** One send attempt with the retry/failure policy. */
    private suspend fun attempt(api: NodeApi, item: OutboxEntity, me: LocalAccount) {
        try {
            if (item.groupId != null && item.recipientUserId.isEmpty()) processGroup(api, item, me) else process(api, item, me)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (item.sentMs != null) { // the one 24 h resend failed: the message itself was delivered to a node before
                outbox.markResent(item.id)
                return
            }
            // REVOKED is not final here: one node could lie it; SyncEngine decides whether the account was disabled
            val permanent = e is PermanentFailure || (e is RpcException &&
                e.status in setOf(Status.STATUS_INVALID, Status.STATUS_NOT_FOUND))
            if (permanent) {
                if (item.groupId != null && item.recipientUserId.isNotEmpty()) groupSender.forgetShare(item)
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

    /**
     * Group item: on first processing make sure every current member has our sender key (SKDMs are queued and sent right
     * here, ahead of the message), then encrypt once and persist the record, like 1:1.
     */
    private suspend fun processGroup(api: NodeApi, item: OutboxEntity, me: LocalAccount) {
        val record = item.recordBytes?.let { Record.parseFrom(it) } ?: run {
            val (dist, skdms) = groupSender.ensureShared(checkNotNull(item.groupId), me.userId)
            // A failing SKDM stays queued with its own backoff; that member parks the message until the key arrives.
            for (id in skdms) outbox.get(id)?.let { attempt(api, it, me) }
            val rec = groupSender.buildRecord(item, me, dist) ?: run {
                db.withTransaction { // nobody left to send to
                    item.messageLocalId?.let { messages.advanceStatusLocal(it, MessageStatus.SENT) }
                    outbox.delete(item.id)
                }
                return
            }
            rec.also { outbox.setRecord(item.id, it.toByteArray()) }
        }
        api.send(record)
        afterSend(item)
    }

    private suspend fun process(api: NodeApi, item: OutboxEntity, me: LocalAccount) {
        if (item.expiresMs in 1..clock.now()) { outbox.delete(item.id); return } // call signaling past its 2 min (D78)
        // D104: nothing more reaches a removed member, also an item encrypted before the removal
        if (contacts.get(item.recipientUserId)?.revoked == true) throw PermanentFailure("member removed")
        val record = item.recordBytes?.let { Record.parseFrom(it) } ?: run {
            val contact = contacts.get(item.recipientUserId) ?: throw PermanentFailure("unknown contact")
            // C23: keys known only from an invite link are not used until the directory confirms them (retried later).
            if (contact.entryVersion == 0L) throw java.io.IOException("contact not confirmed by the directory yet")
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
            RecordCodec.sign(RecordCodec.body(RecordType.ENVELOPE, me.userId, env.toByteArray(), expiresMs = item.expiresMs,
                createdMs = clock.now()), me.accountKey)
                .also { outbox.setRecord(item.id, it.toByteArray()) }
        }
        api.send(record) // the same record every time: nodes deduplicate by author‖id
        afterSend(item)
    }

    /** The account was disabled (verified): nothing will be sent again; unsent messages show as failed. */
    suspend fun cancelAll() = mutex.withLock {
        db.withTransaction {
            outbox.unsentMessageIds().forEach { messages.markFailed(it) }
            outbox.deleteAll()
        }
    }

    private suspend fun afterSend(item: OutboxEntity) {
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
