package app.ottermsg.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Accepted group state. [stateSigned] is the SignedGroupState we accepted; [prevState] the GroupState it replaced (D37). */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey @ColumnInfo(name = "group_id") val groupId: ByteArray,
    val name: String,
    val family: Boolean,
    val version: Long,
    @ColumnInfo(name = "state_signed") val stateSigned: ByteArray,
    @ColumnInfo(name = "prev_state") val prevState: ByteArray?,
    @ColumnInfo(name = "i_am_member") val iAmMember: Boolean,
    @ColumnInfo(name = "created_by") val createdBy: ByteArray,
)

@Entity(tableName = "group_members", primaryKeys = ["group_id", "user_id"], indices = [Index("user_id")])
data class GroupMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: ByteArray,
    @ColumnInfo(name = "user_id") val userId: ByteArray,
    val admin: Boolean,
)

/** A signed update we could not apply yet (D36); retried after every accepted update, dropped after 30 days. */
@Entity(tableName = "pending_group_updates", indices = [Index("group_id")])
data class PendingGroupUpdateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "group_id") val groupId: ByteArray,
    val signed: ByteArray,
    @ColumnInfo(name = "from_user") val fromUser: ByteArray,
    @ColumnInfo(name = "received_ms") val receivedMs: Long,
)

/**
 * A group message we could not show yet; the node copy is already acked.
 * Not [decrypted]: [record] is the ENVELOPE Record (sender key missing). [decrypted]: [record] is the plaintext Content
 * (a sender key decrypts each message only once) waiting for a group state that makes the sender a member.
 */
@Entity(tableName = "pending_group_envelopes", indices = [Index("sender"), Index("group_id")])
data class PendingGroupEnvelopeEntity(
    @PrimaryKey @ColumnInfo(name = "record_key") val recordKey: ByteArray,
    @ColumnInfo(name = "group_id") val groupId: ByteArray?,
    val sender: ByteArray,
    val record: ByteArray,
    @ColumnInfo(name = "received_ms") val receivedMs: Long,
    @ColumnInfo(defaultValue = "0") val decrypted: Boolean = false,
    @ColumnInfo(name = "sent_ms", defaultValue = "0") val sentMs: Long = 0,
)

/** Members that already have our sender key for [distributionId]. */
@Entity(tableName = "sender_key_shared", primaryKeys = ["group_id", "distribution_id", "member"])
data class SenderKeySharedEntity(
    @ColumnInfo(name = "group_id") val groupId: ByteArray,
    @ColumnInfo(name = "distribution_id") val distributionId: ByteArray,
    val member: ByteArray,
)

/** Our current sender-key distribution id per group; deleted on a removal (rotation). */
@Entity(tableName = "my_distribution")
data class MyDistributionEntity(
    @PrimaryKey @ColumnInfo(name = "group_id") val groupId: ByteArray,
    @ColumnInfo(name = "distribution_id") val distributionId: ByteArray,
    @ColumnInfo(name = "since_version") val sinceVersion: Long,
)

/** Frozen recipient set of an outgoing group message (D23) with per-recipient receipt times. */
@Entity(tableName = "message_recipients", primaryKeys = ["message_local_id", "user_id"])
data class MessageRecipientEntity(
    @ColumnInfo(name = "message_local_id") val messageLocalId: Long,
    @ColumnInfo(name = "user_id") val userId: ByteArray,
    @ColumnInfo(name = "delivered_ms") val deliveredMs: Long? = null,
    @ColumnInfo(name = "read_ms") val readMs: Long? = null,
)
