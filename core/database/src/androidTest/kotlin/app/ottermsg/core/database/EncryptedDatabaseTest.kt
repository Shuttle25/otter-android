package app.ottermsg.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ottermsg.core.database.entity.ManifestEntity
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    @Test fun fileIsNotPlainSqlite() = runBlocking {
        System.loadLibrary("sqlcipher")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("enc-test.db")
        val db = Room.databaseBuilder(ctx, OtterDatabase::class.java, "enc-test.db")
            .openHelperFactory(SupportOpenHelperFactory(ByteArray(32) { 7 })).build()
        db.manifestDao().insert(ManifestEntity(1, byteArrayOf(1)))
        db.close()
        val header = ctx.getDatabasePath("enc-test.db").readBytes().copyOf(15).decodeToString()
        assertFalse(header == "SQLite format 3")
    }
}
