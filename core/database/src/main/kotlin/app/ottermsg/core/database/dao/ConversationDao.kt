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
    /** Every 1:1 chat partner (M9.5: refreshed by id, D137). */
    @Query("SELECT peer_user_id FROM conversations WHERE peer_user_id IS NOT NULL") suspend fun peerIds(): List<ByteArray>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun get(id: Long): ConversationEntity?
    @Query("SELECT * FROM conversations ORDER BY id") suspend fun all(): List<ConversationEntity>
    @Query("SELECT * FROM conversations WHERE group_id = :groupId") suspend fun byGroup(groupId: ByteArray): ConversationEntity?
    @Transaction suspend fun getOrCreateGroupConversation(groupId: ByteArray, title: String, nowMs: Long): Long =
        byGroup(groupId)?.id ?: insert(ConversationEntity(groupId = groupId, type = ConversationType.GROUP, title = title, lastActivityMs = nowMs))
            .takeIf { it > 0 } ?: byGroup(groupId)!!.id
    /** The news conversation (D64), if any news has arrived. */
    @Query("SELECT * FROM conversations WHERE type = ${ConversationType.NEWS} ORDER BY id LIMIT 1") suspend fun news(): ConversationEntity?
    @Transaction suspend fun getOrCreateNews(nowMs: Long): Long =
        news()?.id ?: insert(ConversationEntity(title = "", lastActivityMs = nowMs, type = ConversationType.NEWS))
    @Query("UPDATE conversations SET unread = :unread WHERE id = :id") suspend fun setUnread(id: Long, unread: Int)
    @Query("UPDATE conversations SET title = :title WHERE group_id = :groupId") suspend fun setGroupTitle(groupId: ByteArray, title: String)
    @Query("SELECT * FROM conversations WHERE id = :id") fun observe(id: Long): Flow<ConversationEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(c: ConversationEntity): Long
    @Transaction suspend fun getOrCreateDirect(peer: ByteArray, title: String, nowMs: Long): Long =
        byPeer(peer)?.id ?: insert(ConversationEntity(peerUserId = peer, title = title, lastActivityMs = nowMs)).takeIf { it > 0 } ?: byPeer(peer)!!.id
    @Query("UPDATE conversations SET last_activity_ms = MAX(last_activity_ms, :ms) WHERE id = :id") suspend fun touch(id: Long, ms: Long)
    @Query("UPDATE conversations SET unread = unread + 1 WHERE id = :id") suspend fun incrementUnread(id: Long)
    @Query("UPDATE conversations SET unread = 0 WHERE id = :id") suspend fun clearUnread(id: Long)
    @Query("UPDATE conversations SET draft = :draft WHERE id = :id") suspend fun setDraft(id: Long, draft: String)
    /** A re-key (D111): the direct chat of [old] continues with [new]. */
    @Query("UPDATE conversations SET peer_user_id = :new WHERE peer_user_id = :old") suspend fun movePeer(old: ByteArray, new: ByteArray)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE conversations SET title = :title WHERE peer_user_id = :peer") suspend fun setTitle(peer: ByteArray, title: String)
    /** The chat list. The news chat only while it has a message: deleted or revoked news leave nothing to show (D65). */
    @Query("""
        SELECT c.id, c.title, c.peer_user_id, c.unread, c.last_activity_ms,
               m.body AS last_body, m.status AS last_status, m.outgoing AS last_outgoing, m.kind AS last_kind,
               c.group_id, COALESCE(g.family, 0) AS family, m.sender_id AS last_sender_id, m.system_data AS last_system_data,
               c.type AS type, m.call_outcome AS last_call_outcome, m.call_duration_s AS last_call_duration_s
        FROM conversations c
        LEFT JOIN groups g ON g.group_id = c.group_id
        LEFT JOIN messages m ON m.local_id = (SELECT local_id FROM messages WHERE conversation_id = c.id ORDER BY sent_ms DESC, local_id DESC LIMIT 1)
        WHERE c.type != ${ConversationType.NEWS} OR m.local_id IS NOT NULL
        ORDER BY c.last_activity_ms DESC""")
    fun observeSummaries(): Flow<List<ConversationSummary>>
}
