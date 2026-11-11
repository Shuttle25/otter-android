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

    @Test fun migrate2To3() {
        helper.createDatabase("m3.db", 2).use {
            it.execSQL("INSERT INTO manifest(version, raw) VALUES (4, x'04')")
            it.execSQL("INSERT INTO outbox(recipient_user_id, content_bytes, message_local_id, urgent, record_bytes, attempts, next_attempt_ms, created_ms) VALUES (x'01', x'02', 1, 1, NULL, 0, 0, 5)")
        }
        helper.runMigrationsAndValidate("m3.db", 3, true).use { db ->
            db.query("SELECT received_ms FROM manifest").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT resent, sent_ms IS NULL FROM outbox").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)); assertEquals(1, c.getInt(1)) }
            db.query("SELECT COUNT(*) FROM endpoint_stats").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }
}
