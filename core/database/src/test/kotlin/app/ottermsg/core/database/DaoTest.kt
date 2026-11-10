package app.ottermsg.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.database.entity.AccountEntity
import app.ottermsg.core.database.entity.ManifestEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {
    private lateinit var db: OtterDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    @Test fun latestManifest() = runTest {
        assertNull(db.manifestDao().latest())
        db.manifestDao().insert(ManifestEntity(3, byteArrayOf(3)))
        db.manifestDao().insert(ManifestEntity(5, byteArrayOf(5)))
        assertEquals(5L, db.manifestDao().latest()!!.version)
        assertEquals(5L, db.manifestDao().observeLatest().first()!!.version)
    }

    @Test fun account() = runTest {
        assertNull(db.accountDao().get())
        db.accountDao().insert(AccountEntity(ByteArray(16) { 1 }, "mama", "Мама", byteArrayOf(9), 42))
        val a = db.accountDao().get()!!
        assertEquals("mama", a.username)
        assertArrayEquals(byteArrayOf(9), a.seedEnc)
    }
}
