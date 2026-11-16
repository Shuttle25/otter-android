package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.ottermsg.core.database.entity.RekeyEntity

@Dao interface RekeyDao {
    @Query("SELECT * FROM rekeys WHERE old_user_id = :old") suspend fun get(old: ByteArray): RekeyEntity?
    @Query("SELECT * FROM rekeys") suspend fun all(): List<RekeyEntity>
    @Query("SELECT * FROM rekeys WHERE applied_ms = 0") suspend fun pending(): List<RekeyEntity>
    @Upsert suspend fun upsert(r: RekeyEntity)
    @Query("UPDATE rekeys SET applied_ms = :ms WHERE old_user_id = :old") suspend fun markApplied(old: ByteArray, ms: Long)
}
