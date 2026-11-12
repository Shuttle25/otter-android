package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.database.model.ConversationSummary
import kotlinx.coroutines.flow.Flow

@Dao interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE peer_user_id = :peer") suspend fun byPeer(peer: ByteArray): ConversationEntity?
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun get(id: Long): ConversationEntity?
    @Query("SELECT * FROM conversations ORDER BY id") suspend fun all(): List<ConversationEntity>
    @Query("SELECT * FROM conversations WHERE group_id = :groupId") suspend fun byGroup(groupId: ByteArray): ConversationEntity?
    @Transaction suspend fun getOrCreateGroupConversation(groupId: ByteArray, title: String, nowMs: Long): Long =
        byGroup(groupId)?.id ?: insert(ConversationEntity(groupId = groupId, type = ConversationType.GROUP, title = title, lastActivityMs = nowMs))
            .takeIf { it > 0 } ?: byGroup(groupId)!!.id
    @Query("UPDATE conversations SET title = :title WHERE group_id = :groupId") suspend fun setGroupTitle(groupId: ByteArray, title: String)
    @Query("SELECT * FROM conversations WHERE id = :id") fun observe(id: Long): Flow<ConversationEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(c: ConversationEntity): Long
    @Transaction suspend fun getOrCreateDirect(peer: ByteArray, title: String, nowMs: Long): Long =
        byPeer(peer)?.id ?: insert(ConversationEntity(peerUserId = peer, title = title, lastActivityMs = nowMs)).takeIf { it > 0 } ?: byPeer(peer)!!.id
    @Query("UPDATE conversations SET last_activity_ms = MAX(last_activity_ms, :ms) WHERE id = :id") suspend fun touch(id: Long, ms: Long)
    @Query("UPDATE conversations SET unread = unread + 1 WHERE id = :id") suspend fun incrementUnread(id: Long)
    @Query("UPDATE conversations SET unread = 0 WHERE id = :id") suspend fun clearUnread(id: Long)
    @Query("UPDATE conversations SET draft = :draft WHERE id = :id") suspend fun setDraft(id: Long, draft: String)
    @Query("UPDATE conversations SET title = :title WHERE peer_user_id = :peer") suspend fun setTitle(peer: ByteArray, title: String)
    @Query("""
        SELECT c.id, c.title, c.peer_user_id, c.unread, c.last_activity_ms,
               m.body AS last_body, m.status AS last_status, m.outgoing AS last_outgoing, m.kind AS last_kind,
               c.group_id, COALESCE(g.family, 0) AS family, m.sender_id AS last_sender_id, m.system_data AS last_system_data
        FROM conversations c
        LEFT JOIN groups g ON g.group_id = c.group_id
        LEFT JOIN messages m ON m.local_id = (SELECT local_id FROM messages WHERE conversation_id = c.id ORDER BY sent_ms DESC, local_id DESC LIMIT 1)
        ORDER BY c.last_activity_ms DESC""")
    fun observeSummaries(): Flow<List<ConversationSummary>>
}
