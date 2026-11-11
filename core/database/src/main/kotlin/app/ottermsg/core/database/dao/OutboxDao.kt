package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.ottermsg.core.database.entity.OutboxEntity

@Dao interface OutboxDao {
    companion object { const val RESEND_AFTER_MS = 24 * 60 * 60 * 1000L }

    @Insert suspend fun insert(o: OutboxEntity): Long
    /** Unsent items that are due, plus sent data messages without a receipt for 24 h that were not resent yet. */
    @Query("""SELECT * FROM outbox WHERE (sent_ms IS NULL AND next_attempt_ms <= :now)
              OR (sent_ms IS NOT NULL AND resent = 0 AND sent_ms <= :now - $RESEND_AFTER_MS) ORDER BY id LIMIT :limit""")
    suspend fun due(now: Long, limit: Int = 50): List<OutboxEntity>
    /** Earliest time something becomes due (a retry or a 24 h resend). */
    @Query("""SELECT MIN(t) FROM (SELECT next_attempt_ms AS t FROM outbox WHERE sent_ms IS NULL
              UNION ALL SELECT sent_ms + $RESEND_AFTER_MS AS t FROM outbox WHERE sent_ms IS NOT NULL AND resent = 0)""")
    suspend fun nextAttemptAt(): Long?
    @Query("UPDATE outbox SET sent_ms = :now WHERE id = :id") suspend fun markSent(id: Long, now: Long)
    @Query("UPDATE outbox SET resent = 1 WHERE id = :id") suspend fun markResent(id: Long)
    @Query("DELETE FROM outbox WHERE message_local_id = :localId") suspend fun deleteForMessage(localId: Long)
    @Query("DELETE FROM outbox WHERE sent_ms IS NOT NULL AND sent_ms < :cutoff") suspend fun deleteSentBefore(cutoff: Long)
    @Query("UPDATE outbox SET record_bytes = :record WHERE id = :id") suspend fun setRecord(id: Long, record: ByteArray)
    @Query("UPDATE outbox SET attempts = :attempts, next_attempt_ms = :next WHERE id = :id") suspend fun reschedule(id: Long, attempts: Int, next: Long)
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
}
