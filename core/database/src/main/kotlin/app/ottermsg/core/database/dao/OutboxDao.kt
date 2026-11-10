package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.ottermsg.core.database.entity.OutboxEntity

@Dao interface OutboxDao {
    @Insert suspend fun insert(o: OutboxEntity): Long
    @Query("SELECT * FROM outbox WHERE next_attempt_ms <= :now ORDER BY id LIMIT :limit") suspend fun due(now: Long, limit: Int = 50): List<OutboxEntity>
    @Query("SELECT MIN(next_attempt_ms) FROM outbox") suspend fun nextAttemptAt(): Long?
    @Query("UPDATE outbox SET record_bytes = :record WHERE id = :id") suspend fun setRecord(id: Long, record: ByteArray)
    @Query("UPDATE outbox SET attempts = :attempts, next_attempt_ms = :next WHERE id = :id") suspend fun reschedule(id: Long, attempts: Int, next: Long)
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
}
