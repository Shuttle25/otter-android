package app.ottermsg.core.data

import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.proto.v1.Envelope
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Groups against three real nodes, one client per node. Skipped unless OTTER_DEV_MESH_INVITE holds a fresh bootstrap link:
 *   otter-server/scripts/dev-mesh.sh   (a fresh invite: see docs/09 "M3 notes")
 *   OTTER_DEV_MESH_INVITE="otter://join/…" ./gradlew :core:data:testDebugUnitTest --tests '*LiveGroupTest*'
 */
@RunWith(RobolectricTestRunner::class)
class LiveGroupTest {
    private suspend fun LiveClient.texts(gid: ByteArray): List<String> {
        val cid = db.conversationDao().byGroup(gid)?.id ?: return emptyList()
        return messages.observeMessages(cid).first().filter { it.kind == MessageKind.TEXT }.map { it.body }
    }

    @Test fun groupAcrossThreeNodes() = runBlocking {
        val invite = System.getenv("OTTER_DEV_MESH_INVITE")
        assumeTrue("set OTTER_DEV_MESH_INVITE to a fresh dev-mesh bootstrap link", !invite.isNullOrBlank())
        val suffix = (1000..9999).random()
        val a = LiveClient(); val b = LiveClient(); val c = LiveClient()
        try {
            a.register(invite!!, "Анна", "anna_$suffix")
            val onA = a.online(prefer = "a")
            b.register(a.invites.create().link, "Борис", "boris_$suffix")
            val onB = b.online(prefer = "b")
            c.register(a.invites.create().link, "Вера", "vera_$suffix")
            val onC = c.online(prefer = "c")
            assertEquals(listOf("a", "b", "c"), listOf(onA.nodeLabel, onB.nodeLabel, onC.nodeLabel))
            eventually(timeoutMs = 30_000, what = "contacts") {
                listOf(a, b, c).all { x -> listOf(a, b, c).all { y -> x === y || x.db.contactDao().get(y.me.userId) != null } }
            }

            val cid = a.groups.create("Семья $suffix", true, listOf(b.me.userId, c.me.userId))
            val gid = a.db.conversationDao().get(cid)!!.groupId!!
            for (x in listOf(b, c)) eventually(timeoutMs = 30_000, what = "group reaches a member") { x.db.groupDao().get(gid)?.version == 1L }

            // messages from each reach the others
            val sent = mutableMapOf<LiveClient, Long>()
            for ((x, t) in listOf(a to "от Анны", b to "от Бориса", c to "от Веры")) {
                sent[x] = x.messages.sendText(x.db.conversationDao().byGroup(gid)!!.id, t)!!
            }
            for ((x, t) in listOf(a to "от Анны", b to "от Бориса", c to "от Веры")) for (y in listOf(a, b, c)) if (y !== x) {
                eventually(timeoutMs = 30_000, what = "$t reaches a member") { t in y.texts(gid) }
            }
            for (x in listOf(a, b, c)) x.messages.markConversationRead(x.db.conversationDao().byGroup(gid)!!.id)
            for ((x, id) in sent) eventually(timeoutMs = 30_000, what = "READ aggregate") { x.db.messageDao().get(id)!!.status == MessageStatus.READ }

            // remove C: the others keep chatting, C's next decrypt fails
            a.groups.removeMember(gid, c.me.userId)
            eventually(timeoutMs = 30_000, what = "C sees the removal") { c.db.groupDao().get(gid)?.iAmMember == false }
            eventually(timeoutMs = 30_000, what = "B has v2") { b.db.groupDao().get(gid)?.version == 2L }
            b.engine.stop() // keep A's record in its outbox until B is back
            val after = a.messages.sendText(cid, "после удаления")!!
            eventually(timeoutMs = 30_000, what = "A sent it") { a.db.messageDao().get(after)!!.status >= MessageStatus.SENT }
            val rec = Record.parseFrom(a.db.outboxDao().forMessage(after).first().recordBytes!!)
            val body = RecordBody.parseFrom(rec.body)
            val env = Envelope.parseFrom(body.payload)
            assertFalse("C is not a recipient", env.recipientsList.any { it.user.toByteArray().contentEquals(c.me.userId) })
            c.groupReceiver.onEnvelope(a.me.userId, byteArrayOf(7), rec, body, env)
            assertFalse("C cannot read it", "после удаления" in c.texts(gid))
            assertEquals(1, c.db.groupDao().pendingEnvelopesFrom(a.me.userId).size)
            b.online(prefer = "b")
            eventually(timeoutMs = 30_000, what = "B reads it") { "после удаления" in b.texts(gid) }
            b.messages.markConversationRead(b.db.conversationDao().byGroup(gid)!!.id)
            eventually(timeoutMs = 30_000, what = "READ by the remaining member") { a.db.messageDao().get(after)!!.status == MessageStatus.READ }
            val bAfter = b.messages.sendText(b.db.conversationDao().byGroup(gid)!!.id, "Борис после удаления")!!
            eventually(timeoutMs = 30_000, what = "A reads B") { "Борис после удаления" in a.texts(gid) }
            eventually(timeoutMs = 30_000, what = "B's aggregate") { b.db.messageDao().get(bAfter)!!.status >= MessageStatus.DELIVERED }
            delay(2_000)
            assertFalse("C gets nothing new", "Борис после удаления" in c.texts(gid))
            assertTrue(b.db.messageRecipientDao().counts(bAfter).total == 1)
        } finally {
            a.close(); b.close(); c.close()
        }
    }
}
