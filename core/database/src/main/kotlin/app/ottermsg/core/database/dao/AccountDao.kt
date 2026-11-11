package app.ottermsg.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.ottermsg.core.database.entity.AccountEntity

@Dao
interface AccountDao {
    @Insert suspend fun insert(a: AccountEntity)
    @Query("SELECT * FROM account LIMIT 1") suspend fun get(): AccountEntity?
    @Query("UPDATE account SET display_name = :name") suspend fun setDisplayName(name: String)
}
