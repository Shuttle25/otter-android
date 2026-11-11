package app.ottermsg.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.SeedVault
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.invite.InviteRepository
import app.ottermsg.core.data.messaging.MailboxReceiver
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.data.messaging.Outbox
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.registration.TransportConnector
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.signal.PrekeyRefill
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.model.MessageStatus
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.network.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

/**
 * Interop with a real otter-node. Skipped unless OTTER_DEV_INVITE holds a fresh bootstrap link:
 *   otter-server/scripts/dev-node.sh --host 127.0.0.1        (terminal 1; fresh .dev, the invite is single-use)
 *   OTTER_DEV_INVITE="$(cat ../otter-server/.dev/bootstrap-invite.txt)" ./gradlew :core:data:testDebugUnitTest --tests '*LiveNodeTest*'
 */
@RunWith(RobolectricTestRunner::class)
class LiveNodeTest {
    /** One client wired like production: real OkHttp transport, ConnectionManager and SyncEngine. */
    private class LiveClient {
        val clock = Clock { System.currentTimeMillis() }
        val db: OtterDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = OkHttpTransport()
        val settings = Settings(db.settingsDao())
        val accounts = AccountRepository(db.accountDao(), settings, SeedVault(XorWrapper))
        val signal = SignalService(accounts, db.signalKvDao(), db.contactDao(), scope)
        val directory = DirectorySync(accounts, db.contactDao(), db.conversationDao())
        val outbox = Outbox(db, accounts, signal, clock)
        val receiver = MailboxReceiver(db, accounts, signal, directory, outbox, clock)
        val messages = MessageRepository(db, accounts, outbox, clock)
        val prekeys = PrekeyRefill(accounts, settings, signal, clock)
        val stats = app.ottermsg.core.network.InMemoryEndpointStats()
        val selector = app.ottermsg.core.network.NodeSelector(transport, stats, clock::now)
        val engine = SyncEngine(selector, stats, accounts, settings, db.manifestDao(), directory, receiver, outbox, prekeys, scope, true, clock)
        val invites = InviteRepository(accounts, settings, db.manifestDao(), engine, clock)
        val registration = Registration(db, TransportConnector(selector), SeedVault(XorWrapper), accounts, settings, clock, allowPlaintext = true)
        val me get() = accounts.require()

        suspend fun register(link: String, name: String, username: String) =
            registration.register(link, name, username, ByteArray(32).also(SecureRandom()::nextBytes)).getOrThrow()

        suspend fun online() { engine.start(); engine.connection.first { it is ConnectionState.Online } }
        fun close() { engine.stop(); scope.cancel(); db.close() }
    }

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
