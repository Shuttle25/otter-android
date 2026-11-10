package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.SettingEntity

@Dao interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun get(key: String): ByteArray?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: SettingEntity)
}
