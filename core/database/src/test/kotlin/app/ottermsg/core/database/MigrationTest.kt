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

    @Test fun migrate4To5AddsAttachmentsAndHeld() {
        helper.createDatabase("m5.db", 4).use {
            it.execSQL("INSERT INTO conversations(id, peer_user_id, title, last_activity_ms, unread, draft, type) VALUES (7, x'0202', 'Мама', 10, 0, '', 0)")
            it.execSQL("""INSERT INTO messages(local_id, message_id, conversation_id, sender_id, outgoing, sent_ms, received_ms, body, status, kind, read_receipt_sent)
                          VALUES (1, x'01', 7, x'0202', 1, 5, 6, 'привет', 0, 0, 0)""")
            it.execSQL("INSERT INTO outbox(recipient_user_id, content_bytes, message_local_id, urgent, record_bytes, attempts, next_attempt_ms, created_ms, sent_ms, resent) VALUES (x'0202', x'02', 1, 1, NULL, 0, 0, 5, NULL, 0)")
        }
        helper.runMigrationsAndValidate("m5.db", 5, true).use { db ->
            db.query("SELECT held FROM outbox").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT body FROM messages").use { c -> c.moveToFirst(); assertEquals("привет", c.getString(0)) }
            db.query("SELECT COUNT(*) FROM attachments").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test fun migrate5To6AddsResetMs() {
        helper.createDatabase("m6.db", 5).use {
            it.execSQL("""INSERT INTO contacts(user_id, username, display_name, account_pub, identity_pub, verified, identity_changed, revoked, entry_version)
                          VALUES (x'01', 'anna', 'Анна', x'02', x'03', 1, 0, 0, 1)""")
        }
        helper.runMigrationsAndValidate("m6.db", 6, true).use { db ->
            db.query("SELECT username, reset_ms FROM contacts").use { c -> c.moveToFirst(); assertEquals("anna", c.getString(0)); assertEquals(0, c.getInt(1)) }
        }
    }

    @Test fun migrate6To7AddsThrottleColumnsAndNetLog() {
        helper.createDatabase("m7.db", 6).use {
            it.execSQL("INSERT INTO endpoint_stats(`key`, last_success_ms, last_failure_ms, rtt_ms, failures) VALUES ('wss://a', 5, 6, 40, 2)")
        }
        helper.runMigrationsAndValidate("m7.db", 7, true).use { db ->
            db.query("SELECT rtt_ms, failures, throttle_count, throttle_window_start_ms, demoted_until_ms FROM endpoint_stats").use { c ->
                c.moveToFirst()
                assertEquals(40, c.getInt(0)); assertEquals(2, c.getInt(1))
                assertEquals(0, c.getInt(2)); assertEquals(0, c.getInt(3)); assertEquals(0, c.getInt(4))
            }
            db.query("SELECT COUNT(*) FROM net_log").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }
    @Test fun migrate7To8AddsCallColumnsAndOutboxExpiry() {
        helper.createDatabase("m8.db", 7).use {
            it.execSQL("INSERT INTO conversations(id, peer_user_id, title, last_activity_ms, unread, draft, type) VALUES (1, x'02', 'Anna', 1, 0, '', 0)")
            it.execSQL("""INSERT INTO messages(local_id, message_id, conversation_id, sender_id, outgoing, sent_ms, received_ms, body, status, kind, read_receipt_sent)
                          VALUES (1, x'01', 1, x'02', 0, 5, 6, 'hi', 2, 0, 0)""")
            it.execSQL("INSERT INTO outbox(recipient_user_id, content_bytes, message_local_id, urgent, record_bytes, attempts, next_attempt_ms, created_ms, sent_ms, resent, held) VALUES (x'02', x'03', 1, 1, NULL, 0, 0, 5, NULL, 0, 0)")
        }
        helper.runMigrationsAndValidate("m8.db", 8, true).use { db ->
            db.query("SELECT body, call_outcome, call_duration_s FROM messages").use { c ->
                c.moveToFirst(); assertEquals("hi", c.getString(0)); assertEquals(0, c.getInt(1)); assertEquals(0L, c.getLong(2))
            }
            db.query("SELECT COUNT(*) FROM outbox WHERE expires_ms = 0").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }
}
