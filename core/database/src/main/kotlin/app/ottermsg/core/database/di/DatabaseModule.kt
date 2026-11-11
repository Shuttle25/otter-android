package app.ottermsg.core.database.di

import android.content.Context
import androidx.room.Room
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.key.DatabaseKeyProvider
import app.ottermsg.core.database.key.KeystoreKeyWrapper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): OtterDatabase {
        System.loadLibrary("sqlcipher")
        val key = DatabaseKeyProvider(File(ctx.noBackupFilesDir, "db.key"), KeystoreKeyWrapper()).getOrCreate()
        return Room.databaseBuilder(ctx, OtterDatabase::class.java, "otter.db")
            .openHelperFactory(SupportOpenHelperFactory(key))
            .build()
    }
    @Provides fun accountDao(db: OtterDatabase) = db.accountDao()
    @Provides fun manifestDao(db: OtterDatabase) = db.manifestDao()
    @Provides fun contactDao(db: OtterDatabase) = db.contactDao()
    @Provides fun conversationDao(db: OtterDatabase) = db.conversationDao()
    @Provides fun messageDao(db: OtterDatabase) = db.messageDao()
    @Provides fun outboxDao(db: OtterDatabase) = db.outboxDao()
    @Provides fun seenEnvelopeDao(db: OtterDatabase) = db.seenEnvelopeDao()
    @Provides fun signalKvDao(db: OtterDatabase) = db.signalKvDao()
    @Provides fun settingsDao(db: OtterDatabase) = db.settingsDao()
    @Provides fun endpointStatDao(db: OtterDatabase) = db.endpointStatDao()
    @Provides fun groupDao(db: OtterDatabase) = db.groupDao()
    @Provides fun messageRecipientDao(db: OtterDatabase) = db.messageRecipientDao()
}
