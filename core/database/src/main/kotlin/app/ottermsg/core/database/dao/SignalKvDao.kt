package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.SignalKvEntity

/** Blocking: called from libsignal store callbacks on a background thread. */
@Dao interface SignalKvDao {
    @Query("SELECT v FROM signal_kv WHERE table_id = :t AND k = :k") fun get(t: Int, k: String): ByteArray?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(e: SignalKvEntity)
    @Query("DELETE FROM signal_kv WHERE table_id = :t AND k = :k") fun delete(t: Int, k: String)
    @Query("SELECT k FROM signal_kv WHERE table_id = :t") fun keys(t: Int): List<String>
}
