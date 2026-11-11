package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.model.AttachmentState
import kotlinx.coroutines.flow.Flow

@Dao interface AttachmentDao {
    @Insert suspend fun insert(a: AttachmentEntity): Long
    @Update suspend fun update(a: AttachmentEntity)
    @Query("SELECT * FROM attachments WHERE id = :id") suspend fun get(id: Long): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE message_local_id = :m") suspend fun forMessage(m: Long): AttachmentEntity?
    @Query("SELECT * FROM attachments WHERE message_local_id = :m") fun observeMessage(m: Long): Flow<AttachmentEntity?>
    @Query("SELECT * FROM attachments WHERE message_local_id IN (SELECT local_id FROM messages WHERE conversation_id = :cid)")
    fun observeConversation(cid: Long): Flow<List<AttachmentEntity>>
    /** Uploads and wanted downloads that are due. */
    @Query("""SELECT * FROM attachments WHERE state IN (${AttachmentState.UPLOADING}, ${AttachmentState.QUEUED})
              AND next_attempt_ms <= :now ORDER BY id LIMIT 8""")
    suspend fun due(now: Long): List<AttachmentEntity>
    @Query("SELECT MIN(next_attempt_ms) FROM attachments WHERE state IN (${AttachmentState.UPLOADING}, ${AttachmentState.QUEUED})")
    suspend fun nextAttemptAt(): Long?
    @Query("SELECT * FROM attachments WHERE state = ${AttachmentState.REMOTE} AND auto = 1") suspend fun autoRemote(): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE ack_pending = 1 LIMIT 32") suspend fun acksPending(): List<AttachmentEntity>
    @Query("UPDATE attachments SET done_chunks = :n WHERE id = :id") suspend fun progress(id: Long, n: Int)
    @Query("DELETE FROM attachments WHERE message_local_id = :m") suspend fun deleteForMessage(m: Long)
    @Query("SELECT file FROM attachments WHERE file IS NOT NULL") suspend fun files(): List<String>
}
