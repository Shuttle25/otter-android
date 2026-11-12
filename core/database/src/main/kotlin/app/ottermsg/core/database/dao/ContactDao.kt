package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.ottermsg.core.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: ContactEntity)
    @Query("SELECT * FROM contacts WHERE user_id = :id") suspend fun get(id: ByteArray): ContactEntity?
    @Query("SELECT * FROM contacts") suspend fun all(): List<ContactEntity>
    @Query("UPDATE contacts SET reset_ms = :ms WHERE user_id = :id") suspend fun setResetMs(id: ByteArray, ms: Long)
    @Query("SELECT * FROM contacts WHERE revoked = 0 ORDER BY display_name COLLATE NOCASE") fun observeAll(): Flow<List<ContactEntity>>
    @Query("UPDATE contacts SET identity_changed = 1, verified = 0 WHERE user_id = :id") suspend fun markIdentityChanged(id: ByteArray)
}
