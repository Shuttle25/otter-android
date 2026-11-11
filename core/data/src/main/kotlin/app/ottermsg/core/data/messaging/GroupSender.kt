package app.ottermsg.core.data.messaging

import androidx.room.withTransaction
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.core.data.bs
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.MyDistributionEntity
import app.ottermsg.core.database.entity.OutboxEntity
import app.ottermsg.core.database.entity.SenderKeySharedEntity
import app.ottermsg.proto.v1.Content
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.Recipient
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordType
import app.ottermsg.proto.v1.SenderKeyDistribution
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Prepares group sends for the [Outbox]: our sender key per group, its distribution to members, the SENDER_KEY record. */
@Singleton
class GroupSender @Inject constructor(private val db: OtterDatabase, private val signal: SignalService, private val clock: Clock) {
    private val groups = db.groupDao()

    /**
     * Ensures our distribution id for [groupId] exists (a fresh one after a rotation) and queues our SKDM 1:1 for every
     * current member that lacks it, recording the shares. Returns the distribution id and the new outbox item ids.
     */
    suspend fun ensureShared(groupId: ByteArray, me: ByteArray): Pair<UUID, List<Long>> {
        val g = groups.get(groupId) ?: throw PermanentFailure("unknown group")
        if (!g.iAmMember) throw PermanentFailure("not a member")
        groups.insertMyDistribution(MyDistributionEntity(groupId, uuidBytes(UUID.randomUUID()), g.version))
        val dist = uuid(checkNotNull(groups.myDistribution(groupId)).distributionId)
        val have = groups.sharedWith(groupId, uuidBytes(dist)).map { it.toList() }.toSet()
        val lacking = groups.members(groupId).map { it.userId }.filter { !it.contentEquals(me) && it.toList() !in have }
        if (lacking.isEmpty()) return dist to emptyList()
        val skdm = signal.withGroups { it.distribution(dist) }
        val content = Content.newBuilder().setSenderKey(SenderKeyDistribution.newBuilder().setGroupId(bs(groupId))
            .setDistributionId(bs(uuidBytes(dist))).setMessage(bs(skdm))).setSentMs(clock.now()).build().toByteArray()
        val ids = db.withTransaction {
            groups.insertShared(lacking.map { SenderKeySharedEntity(groupId, uuidBytes(dist), it) })
            lacking.map { m ->
                db.outboxDao().insert(OutboxEntity(recipientUserId = m, contentBytes = content, messageLocalId = null, urgent = false,
                    createdMs = clock.now(), groupId = groupId))
            }
        }
        return dist to ids
    }

    /**
     * Encrypts [item] once with our sender key and builds the signed SENDER_KEY record for the frozen recipient set,
     * minus anyone no longer in the group (never send to a removed member). Null when nobody is left to receive it.
     */
    suspend fun buildRecord(item: OutboxEntity, me: LocalAccount, dist: UUID): Record? {
        val groupId = checkNotNull(item.groupId)
        val current = groups.members(groupId).map { it.userId.toList() }.toSet()
        val frozen = item.messageLocalId?.let { id -> db.messageRecipientDao().forMessage(id).map { it.userId } }
            ?: groups.members(groupId).map { it.userId }
        val to = frozen.filter { it.toList() in current && !it.contentEquals(me.userId) }
        if (to.isEmpty()) return null
        val ct = signal.withGroups { it.encrypt(dist, item.contentBytes) }
        val env = Envelope.newBuilder().setSenderUser(bs(me.userId)).setSenderDevice(1).setKind(Envelope.Kind.SENDER_KEY)
            .setUrgent(item.urgent).setSharedContent(bs(ct))
            .addAllRecipients(to.map { Recipient.newBuilder().setUser(bs(it)).setDevice(1).build() }).build()
        return RecordCodec.sign(RecordCodec.body(RecordType.ENVELOPE, me.userId, env.toByteArray(), createdMs = clock.now()), me.accountKey)
    }

    /** An SKDM that can never be delivered: forget the share so the next send queues it again. */
    suspend fun forgetShare(item: OutboxEntity) {
        val gid = item.groupId ?: return
        groups.deleteShared(gid, item.recipientUserId)
    }

    companion object {
        fun uuidBytes(u: UUID): ByteArray = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        fun uuid(b: ByteArray): UUID = ByteBuffer.wrap(b).let { UUID(it.long, it.long) }
    }
}
