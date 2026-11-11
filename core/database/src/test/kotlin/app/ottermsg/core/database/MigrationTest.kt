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

    @Test fun migrate3To4KeepsDirectConversations() {
        helper.createDatabase("m4.db", 3).use {
            it.execSQL("INSERT INTO conversations(id, peer_user_id, title, last_activity_ms, unread, draft) VALUES (7, x'0202', 'Мама', 10, 2, 'черновик')")
            it.execSQL("""INSERT INTO messages(local_id, message_id, conversation_id, sender_id, outgoing, sent_ms, received_ms, body, status, kind, read_receipt_sent)
                          VALUES (1, x'01', 7, x'0202', 0, 5, 6, 'привет', 2, 0, 1)""")
            it.execSQL("INSERT INTO outbox(recipient_user_id, content_bytes, message_local_id, urgent, record_bytes, attempts, next_attempt_ms, created_ms, sent_ms, resent) VALUES (x'0202', x'02', 1, 1, NULL, 0, 0, 5, NULL, 0)")
        }
        helper.runMigrationsAndValidate("m4.db", 4, true).use { db ->
            db.query("SELECT id, title, peer_user_id, unread, draft, type, group_id IS NULL FROM conversations").use { c ->
                c.moveToFirst()
                assertEquals(7, c.getInt(0)); assertEquals("Мама", c.getString(1)); assertEquals(2, c.getBlob(2).size)
                assertEquals(2, c.getInt(3)); assertEquals("черновик", c.getString(4)); assertEquals(0, c.getInt(5)); assertEquals(1, c.getInt(6))
            }
            db.query("SELECT body, conversation_id, system_data IS NULL FROM messages").use { c ->
                c.moveToFirst(); assertEquals("привет", c.getString(0)); assertEquals(7, c.getInt(1)); assertEquals(1, c.getInt(2))
            }
            db.query("SELECT group_id IS NULL FROM outbox").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }
}
