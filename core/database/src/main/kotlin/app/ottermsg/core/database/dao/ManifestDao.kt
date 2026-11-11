package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.ManifestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ManifestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: ManifestEntity)
    @Query("SELECT * FROM manifest ORDER BY version DESC LIMIT 1") suspend fun latest(): ManifestEntity?
    @Query("SELECT * FROM manifest ORDER BY version DESC LIMIT 1") fun observeLatest(): Flow<ManifestEntity?>
    /** The latest and the previous manifest (fallback endpoints after an update). */
    @Query("SELECT * FROM manifest ORDER BY version DESC LIMIT 2") suspend fun latestTwo(): List<ManifestEntity>
}
