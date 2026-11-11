package app.ottermsg.core.data

import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Interop with a real otter-node. Skipped unless OTTER_DEV_INVITE holds a fresh bootstrap link:
 *   otter-server/scripts/dev-node.sh --host 127.0.0.1        (terminal 1; fresh .dev, the invite is single-use)
 *   OTTER_DEV_INVITE="$(cat ../otter-server/.dev/bootstrap-invite.txt)" ./gradlew :core:data:testDebugUnitTest --tests '*LiveNodeTest*'
 */
@RunWith(RobolectricTestRunner::class)
class LiveNodeTest {
    @Test fun twoClientsExchangeMessagesThroughRealNode() = runBlocking {
        val invite = System.getenv("OTTER_DEV_INVITE")
        assumeTrue("set OTTER_DEV_INVITE to a fresh dev-node bootstrap link", !invite.isNullOrBlank())
        val suffix = (1000..9999).random()
        val a = LiveClient(); val b = LiveClient()
        try {
            a.register(invite!!, "Мама", "mama_$suffix")
            a.online()
            val link = a.invites.create().link
            b.register(link, "Боря", "borya_$suffix")
            b.online()
            eventually(what = "A sees B via directory event") { a.db.contactDao().get(b.me.userId) != null }

            val bc = b.messages.conversationWith(a.me.userId)
            val local = b.messages.sendText(bc, "Привет")!!
            val ac = a.messages.conversationWith(b.me.userId)
            eventually(what = "A receives") { a.messages.observeMessages(ac).first().any { it.body == "Привет" } }
            eventually(what = "B sees DELIVERED") { b.db.messageDao().get(local)!!.status == MessageStatus.DELIVERED }
            a.messages.markConversationRead(ac)
            eventually(what = "B sees READ") { b.db.messageDao().get(local)!!.status == MessageStatus.READ }

            val reply = a.messages.sendText(ac, "И тебе привет")!!
            eventually(what = "B receives reply") { b.messages.observeMessages(bc).first().any { it.body == "И тебе привет" } }
            eventually(what = "reply DELIVERED") { a.db.messageDao().get(reply)!!.status == MessageStatus.DELIVERED }
        } finally {
            a.close(); b.close()
        }
    }
}
