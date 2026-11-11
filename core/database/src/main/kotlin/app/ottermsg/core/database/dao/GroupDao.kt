package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.ottermsg.core.database.entity.GroupEntity
import app.ottermsg.core.database.entity.GroupMemberEntity
import app.ottermsg.core.database.entity.MyDistributionEntity
import app.ottermsg.core.database.entity.PendingGroupEnvelopeEntity
import app.ottermsg.core.database.entity.PendingGroupUpdateEntity
import app.ottermsg.core.database.entity.SenderKeySharedEntity
import kotlinx.coroutines.flow.Flow

@Dao interface GroupDao {
    companion object { const val PENDING_TTL_MS = 30L * 24 * 60 * 60 * 1000 }

    @Query("SELECT * FROM groups WHERE group_id = :groupId") suspend fun get(groupId: ByteArray): GroupEntity?
    @Query("SELECT * FROM groups WHERE group_id = :groupId") fun observe(groupId: ByteArray): Flow<GroupEntity?>
    @Upsert suspend fun upsert(g: GroupEntity)
    @Query("SELECT * FROM groups WHERE i_am_member = 1") suspend fun groupsIAmIn(): List<GroupEntity>

    @Query("SELECT * FROM group_members WHERE group_id = :groupId") suspend fun members(groupId: ByteArray): List<GroupMemberEntity>
    @Query("SELECT * FROM group_members WHERE group_id = :groupId") fun observeMembers(groupId: ByteArray): Flow<List<GroupMemberEntity>>
    @Query("SELECT COUNT(*) FROM group_members WHERE group_id = :groupId AND user_id = :userId") suspend fun isMember(groupId: ByteArray, userId: ByteArray): Int
    @Query("DELETE FROM group_members WHERE group_id = :groupId") suspend fun clearMembers(groupId: ByteArray)
    @Query("SELECT group_id FROM group_members WHERE user_id = :userId") suspend fun groupsWithMember(userId: ByteArray): List<ByteArray>
    @Insert suspend fun insertMembers(m: List<GroupMemberEntity>)
    @Transaction suspend fun replaceMembers(groupId: ByteArray, m: List<GroupMemberEntity>) { clearMembers(groupId); insertMembers(m) }

    @Insert suspend fun insertPendingUpdate(p: PendingGroupUpdateEntity): Long
    @Query("SELECT * FROM pending_group_updates WHERE group_id = :groupId ORDER BY id") suspend fun pendingUpdates(groupId: ByteArray): List<PendingGroupUpdateEntity>
    @Query("DELETE FROM pending_group_updates WHERE id = :id") suspend fun deletePendingUpdate(id: Long)
    @Query("DELETE FROM pending_group_updates WHERE received_ms < :cutoff") suspend fun prunePendingUpdates(cutoff: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPendingEnvelope(p: PendingGroupEnvelopeEntity): Long
    @Upsert suspend fun upsertPendingEnvelope(p: PendingGroupEnvelopeEntity)
    @Query("SELECT * FROM pending_group_envelopes WHERE sender = :sender ORDER BY received_ms") suspend fun pendingEnvelopesFrom(sender: ByteArray): List<PendingGroupEnvelopeEntity>
    @Query("SELECT * FROM pending_group_envelopes WHERE group_id = :groupId ORDER BY received_ms") suspend fun pendingEnvelopesFor(groupId: ByteArray): List<PendingGroupEnvelopeEntity>
    @Query("SELECT * FROM pending_group_envelopes WHERE received_ms < :cutoff") suspend fun pendingEnvelopesBefore(cutoff: Long): List<PendingGroupEnvelopeEntity>
    @Query("DELETE FROM pending_group_envelopes WHERE record_key = :key") suspend fun deletePendingEnvelope(key: ByteArray)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertShared(s: List<SenderKeySharedEntity>)
    @Query("SELECT member FROM sender_key_shared WHERE group_id = :groupId AND distribution_id = :distId") suspend fun sharedWith(groupId: ByteArray, distId: ByteArray): List<ByteArray>
    @Query("DELETE FROM sender_key_shared WHERE group_id = :groupId") suspend fun clearShared(groupId: ByteArray)
    @Query("DELETE FROM sender_key_shared WHERE group_id = :groupId AND member = :member") suspend fun deleteShared(groupId: ByteArray, member: ByteArray)

    @Query("SELECT * FROM my_distribution WHERE group_id = :groupId") suspend fun myDistribution(groupId: ByteArray): MyDistributionEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMyDistribution(d: MyDistributionEntity): Long
    @Query("DELETE FROM my_distribution WHERE group_id = :groupId") suspend fun deleteMyDistribution(groupId: ByteArray)
}
