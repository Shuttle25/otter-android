package app.ottermsg.core.data

import app.ottermsg.core.data.backup.BackupManager
import app.ottermsg.core.database.model.MessageKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M6 acceptance against real nodes: anna backs up, loses her phone, restores with username + password through another
 * node; boris keeps writing (1:1 and group) without doing anything; the old install ends superseded.
 * Skipped unless OTTER_DEV_INVITE holds a fresh bootstrap link:
 *   OTTER_DEV_INVITE="otter://join/…" OTTER_PREFER_A=a OTTER_PREFER_B=c ./gradlew :core:data:testDebugUnitTest --tests '*LiveRestoreTest*' --rerun
 */
@RunWith(RobolectricTestRunner::class)
class LiveRestoreTest {
    private suspend fun LiveClient.texts(peer: ByteArray) =
        messages.observeMessages(messages.conversationWith(peer)).first().filter { it.kind == MessageKind.TEXT }.map { it.body }
    private suspend fun LiveClient.groupTexts(gid: ByteArray) =
        db.conversationDao().byGroup(gid)?.let { db.messageDao().observe(it.id).first() }.orEmpty().filter { it.kind == MessageKind.TEXT }.map { it.body }

    @Test fun restoreThroughAnotherNodeKeepsChatting() = runBlocking<Unit> {
        val invite = System.getenv("OTTER_DEV_INVITE")
        assumeTrue("set OTTER_DEV_INVITE to a fresh bootstrap link", !invite.isNullOrBlank())
        val preferA = System.getenv("OTTER_PREFER_A"); val preferB = System.getenv("OTTER_PREFER_B")
        val suffix = (1000..9999).random()
        val pw = "korova lodka zima sem $suffix"
        val anna = LiveClient(); val boris = LiveClient(); val restored = LiveClient()
        try {
            anna.register(invite!!, "Анна", "anna_$suffix"); anna.online(preferA)
            boris.register(anna.invites.create().link, "Борис", "boris_$suffix"); boris.online(preferB)
            eventually(timeoutMs = 30_000, what = "anna sees boris") { anna.db.contactDao().get(boris.me.userId) != null }
            anna.messages.sendText(anna.messages.conversationWith(boris.me.userId), "привет")
            eventually(timeoutMs = 30_000, what = "boris reads привет") { "привет" in boris.texts(anna.me.userId) }
            boris.messages.sendText(boris.messages.conversationWith(anna.me.userId), "как дела")
            eventually(timeoutMs = 30_000, what = "anna reads как дела") { "как дела" in anna.texts(boris.me.userId) }
            val gcid = anna.groups.create("Семья $suffix", false, listOf(boris.me.userId))
            val gid = checkNotNull(anna.db.conversationDao().get(gcid)!!.groupId)
            anna.messages.sendText(gcid, "всем")
            eventually(timeoutMs = 30_000, what = "boris reads всем") { "всем" in boris.groupTexts(gid) }
            assertEquals(BackupManager.OK, anna.backups.setPassword(pw)) // full Argon2 parameters
            anna.engine.stop() // the phone is lost

            val link = boris.invites.create().link
            restored.preferFor(link, preferB)
            // the backup has to reach the restoring node first (until then it answers with a fake salt, D51)
            val salt = anna.settings.backupSalt()!!
            val bundle = app.ottermsg.core.crypto.InviteLink.decode(link)
            val m = app.ottermsg.core.crypto.ManifestVerifier.verify(bundle.manifest.toByteArray(), bundle.networkPub.toByteArray(), 0, 0)
            eventually(timeoutMs = 120_000, what = "backup on the restoring node") {
                app.ottermsg.core.data.registration.TransportConnector(restored.selector).withNode(app.ottermsg.core.network.Endpoints.candidates(m, true)) {
                    it.backupAuth("anna_$suffix").salt.toByteArray().contentEquals(salt)
                }
            }
            restored.restore.withPassword(link, "anna_$suffix", pw).getOrThrow()
            assertEquals(listOf("привет", "как дела"), restored.texts(boris.me.userId))
            assertTrue(restored.groupTexts(gid).contains("всем"))
            restored.online(preferB)
            eventually(timeoutMs = 60_000, what = "boris sees the reset") {
                boris.messages.observeMessages(boris.messages.conversationWith(anna.me.userId)).first().any { it.kind == MessageKind.SYSTEM_RESET }
            }
            boris.messages.sendText(boris.messages.conversationWith(anna.me.userId), "ты вернулась!")
            eventually(timeoutMs = 60_000, what = "restored anna reads it") { "ты вернулась!" in restored.texts(boris.me.userId) }
            restored.messages.sendText(restored.messages.conversationWith(boris.me.userId), "да")
            eventually(timeoutMs = 60_000, what = "boris reads да") { "да" in boris.texts(anna.me.userId) }
            boris.messages.sendText(boris.db.conversationDao().byGroup(gid)!!.id, "в группе от бориса")
            eventually(timeoutMs = 60_000, what = "restored anna reads the group") { "в группе от бориса" in restored.groupTexts(gid) }
            restored.messages.sendText(restored.db.conversationDao().byGroup(gid)!!.id, "в группе от анны")
            eventually(timeoutMs = 60_000, what = "boris reads the group") { "в группе от анны" in boris.groupTexts(gid) }

            anna.engine.start() // the old phone comes back
            eventually(timeoutMs = 60_000, what = "old phone superseded") { anna.settings.superseded() }
        } finally {
            anna.close(); boris.close(); restored.close()
        }
    }
}
