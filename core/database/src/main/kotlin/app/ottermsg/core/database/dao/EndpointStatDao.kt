package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.EndpointStatEntity

@Dao interface EndpointStatDao {
    @Query("SELECT * FROM endpoint_stats WHERE `key` = :key") suspend fun get(key: String): EndpointStatEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: EndpointStatEntity)
    @Query("SELECT * FROM endpoint_stats") suspend fun all(): List<EndpointStatEntity>
}
