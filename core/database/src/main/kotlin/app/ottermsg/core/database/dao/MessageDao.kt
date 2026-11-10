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
    @Query("""UPDATE messages SET status = :status WHERE message_id = :messageId AND outgoing = 1
              AND status < :status AND status != ${MessageStatus.FAILED}""")
    suspend fun advanceStatus(messageId: ByteArray, status: Int): Int
    @Query("""UPDATE messages SET status = :status WHERE local_id = :localId AND status < :status AND status != ${MessageStatus.FAILED}""")
    suspend fun advanceStatusLocal(localId: Long, status: Int): Int
    @Query("UPDATE messages SET status = ${MessageStatus.FAILED} WHERE local_id = :localId") suspend fun markFailed(localId: Long)
    @Query("SELECT * FROM messages WHERE conversation_id = :cid AND outgoing = 0 AND kind = ${MessageKind.TEXT} AND read_receipt_sent = 0")
    suspend fun unreadReceiptsPending(cid: Long): List<MessageEntity>
    @Query("UPDATE messages SET read_receipt_sent = 1 WHERE local_id IN (:ids)") suspend fun markReadReceiptSent(ids: List<Long>)
    @Query("DELETE FROM messages WHERE local_id = :localId") suspend fun delete(localId: Long)
}
