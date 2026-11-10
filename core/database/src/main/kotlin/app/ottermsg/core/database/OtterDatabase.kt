package app.ottermsg.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.ottermsg.core.database.dao.AccountDao
import app.ottermsg.core.database.dao.ManifestDao
import app.ottermsg.core.database.entity.AccountEntity
import app.ottermsg.core.database.entity.ManifestEntity

@Database(entities = [AccountEntity::class, ManifestEntity::class], version = 1, exportSchema = true)
abstract class OtterDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun manifestDao(): ManifestDao
}
