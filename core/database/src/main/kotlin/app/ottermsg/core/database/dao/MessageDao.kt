package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: MessageEntity): Long
    @Query("SELECT * FROM messages WHERE conversation_id = :cid ORDER BY sent_ms, local_id") fun observe(cid: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE local_id = :id") suspend fun get(id: Long): MessageEntity?
    @Query("SELECT * FROM messages ORDER BY local_id") suspend fun all(): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE local_id = :id") fun observeOne(id: Long): Flow<MessageEntity?>
    @Query("""UPDATE messages SET status = :status WHERE message_id = :messageId AND outgoing = 1
              AND status < :status AND status != ${MessageStatus.FAILED}""")
    suspend fun advanceStatus(messageId: ByteArray, status: Int): Int
    /** Like [advanceStatus] but only for an outgoing message in [conversationId] (receipts come from that peer). */
    @Query("""UPDATE messages SET status = :status WHERE message_id = :messageId AND conversation_id = :conversationId
              AND outgoing = 1 AND status < :status AND status != ${MessageStatus.FAILED}""")
    suspend fun advanceStatusIn(conversationId: Long, messageId: ByteArray, status: Int): Int
    @Query("SELECT * FROM messages WHERE message_id = :messageId") suspend fun byMessageId(messageId: ByteArray): MessageEntity?
    @Query("""UPDATE messages SET status = :status WHERE local_id = :localId AND status < :status AND status != ${MessageStatus.FAILED}""")
    suspend fun advanceStatusLocal(localId: Long, status: Int): Int
    @Query("UPDATE messages SET status = ${MessageStatus.FAILED} WHERE local_id = :localId") suspend fun markFailed(localId: Long)
    @Query("UPDATE messages SET status = ${MessageStatus.PENDING} WHERE local_id = :localId AND status = ${MessageStatus.FAILED}")
    suspend fun resetFailed(localId: Long): Int
    @Query("SELECT * FROM messages WHERE conversation_id = :cid AND outgoing = 0 AND kind IN (${MessageKind.TEXT}, ${MessageKind.PHOTO}) AND read_receipt_sent = 0")
    suspend fun unreadReceiptsPending(cid: Long): List<MessageEntity>
    @Query("UPDATE messages SET read_receipt_sent = 1 WHERE local_id IN (:ids)") suspend fun markReadReceiptSent(ids: List<Long>)
    @Query("DELETE FROM messages WHERE local_id = :localId") suspend fun delete(localId: Long)
    /** Deletes the news message with record key [messageId] (ANNOUNCEMENT_DELETED, revoked key). */
    @Query("DELETE FROM messages WHERE message_id = :messageId AND kind = ${MessageKind.NEWS}") suspend fun deleteNews(messageId: ByteArray): Int
    @Query("SELECT * FROM messages WHERE kind = ${MessageKind.NEWS} ORDER BY local_id") suspend fun allNews(): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :cid") suspend fun count(cid: Long): Int
}
