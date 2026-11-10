package app.ottermsg.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OtterDatabase::class.java)

    @Test fun migrate1To2() {
        helper.createDatabase("m.db", 1).use { it.execSQL("INSERT INTO manifest(version, raw) VALUES (1, x'01')") }
        helper.runMigrationsAndValidate("m.db", 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM manifest").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }
}
