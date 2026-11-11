package app.ottermsg.core.data

import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Failover against three real nodes. Skipped unless OTTER_DEV_MESH_INVITE holds a fresh bootstrap link:
 *   otter-server/scripts/dev-mesh.sh --host 127.0.0.1 [--reset --yes]
 *   OTTER_DEV_MESH_INVITE="$(cat ../otter-server/.dev-mesh/bootstrap-invite.txt)" \
 *   OTTER_DEV_MESH_SCRIPT=../otter-server/scripts/dev-mesh.sh ./gradlew :core:data:testDebugUnitTest --tests '*LiveMeshTest*'
 * With OTTER_DEV_MESH_SCRIPT set the test stops the node A uses (`dev-mesh.sh kill <n>`) and starts it again at the end.
 */
@RunWith(RobolectricTestRunner::class)
class LiveMeshTest {
    private fun mesh(vararg args: String) {
        val script = System.getenv("OTTER_DEV_MESH_SCRIPT") ?: return
        val p = ProcessBuilder(script, *args).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
    }

    @Test fun failoverKeepsTheConversationGoing() = runBlocking {
        val invite = System.getenv("OTTER_DEV_MESH_INVITE")
        assumeTrue("set OTTER_DEV_MESH_INVITE to a fresh dev-mesh bootstrap link", !invite.isNullOrBlank())
        val suffix = (1000..9999).random()
        val a = LiveClient(); val b = LiveClient()
        var killed: String? = null
        try {
            a.register(invite!!, "Мама", "mama_$suffix")
            val first = a.online()
            assertEquals(setOf("a", "b", "c"), a.manifests.candidates(true).map { it.label }.toSet())
            val link = a.invites.create().link
            b.register(link, "Боря", "borya_$suffix")
            b.online()
            eventually(what = "A sees B") { a.db.contactDao().get(b.me.userId) != null }

            if (System.getenv("OTTER_DEV_MESH_SCRIPT") != null) {
                killed = first.nodeLabel
                mesh("kill", first.nodeLabel)
                val next = withTimeout(15_000) {
                    a.engine.connection.first { it is ConnectionState.Online && it.nodeLabel != first.nodeLabel }
                } as ConnectionState.Online
                assertNotEquals(first.nodeLabel, next.nodeLabel)
            }

            val bc = b.messages.conversationWith(a.me.userId)
            val local = b.messages.sendText(bc, "после отказа узла")!!
            val ac = a.messages.conversationWith(b.me.userId)
            eventually(timeoutMs = 30_000, what = "A receives") { a.messages.observeMessages(ac).first().any { it.body == "после отказа узла" } }
            eventually(timeoutMs = 30_000, what = "DELIVERED") { b.db.messageDao().get(local)!!.status >= MessageStatus.DELIVERED }
            a.messages.markConversationRead(ac)
            eventually(timeoutMs = 30_000, what = "READ") { b.db.messageDao().get(local)!!.status == MessageStatus.READ }
        } finally {
            a.close(); b.close()
            killed?.let { mesh("start", it) }
        }
    }
}
