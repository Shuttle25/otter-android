package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.SeenEnvelopeEntity

@Dao interface SeenEnvelopeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: SeenEnvelopeEntity): Long
    @Query("DELETE FROM seen_envelopes WHERE until_ms < :now") suspend fun prune(now: Long)
    @Query("DELETE FROM seen_envelopes WHERE record_key = :k") suspend fun delete(k: ByteArray)
}
