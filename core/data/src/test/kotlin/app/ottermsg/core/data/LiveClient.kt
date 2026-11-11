package app.ottermsg.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.SeedVault
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.invite.InviteRepository
import app.ottermsg.core.data.group.GroupApplier
import app.ottermsg.core.data.group.GroupRepository
import app.ottermsg.core.data.messaging.GroupReceiver
import app.ottermsg.core.data.messaging.GroupSender
import app.ottermsg.core.data.messaging.MailboxReceiver
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.data.messaging.Outbox
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.registration.TransportConnector
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.signal.PrekeyRefill
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.data.sync.ManifestUpdater
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.network.InMemoryEndpointStats
import app.ottermsg.core.network.NodeSelector
import app.ottermsg.core.network.OkHttpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import java.security.SecureRandom

/** One client wired like production (real OkHttp transport, racing, SyncEngine) for the live-node tests. */
class LiveClient {
    val clock = Clock { System.currentTimeMillis() }
    val db: OtterDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = OkHttpTransport()
    val settings = Settings(db.settingsDao())
    val accounts = AccountRepository(db.accountDao(), settings, SeedVault(XorWrapper))
    val signal = SignalService(accounts, db.signalKvDao(), db.contactDao(), scope)
    val directory = DirectorySync(accounts, db.contactDao(), db.conversationDao())
    val outbox = Outbox(db, accounts, signal, clock, GroupSender(db, signal, clock))
    val applier = GroupApplier(db, accounts, directory, clock)
    val groupReceiver = GroupReceiver(db, accounts, signal, applier, outbox, clock)
    val receiver = MailboxReceiver(db, accounts, signal, directory, outbox, clock, groupReceiver)
    val groups = GroupRepository(db, accounts, applier, outbox, clock)
    val messages = MessageRepository(db, accounts, outbox, clock)
    val prekeys = PrekeyRefill(accounts, settings, signal, clock)
    val stats = InMemoryEndpointStats()
    val selector = NodeSelector(transport, stats, clock::now)
    val manifests = ManifestUpdater(db.manifestDao(), settings, clock)
    val engine = SyncEngine(stats, selector, accounts, settings, manifests, directory, receiver, outbox, prekeys, scope, true, clock)
    val invites = InviteRepository(accounts, settings, db.manifestDao(), engine, clock)
    val registration = Registration(db, TransportConnector(selector), SeedVault(XorWrapper), accounts, settings, clock, allowPlaintext = true)
    val me get() = accounts.require()

    suspend fun register(link: String, name: String, username: String) =
        registration.register(link, name, username, ByteArray(32).also(SecureRandom()::nextBytes)).getOrThrow()

    /**
     * Connects; with [prefer] the race starts at that node (seeded stats: others recently failed), so a test can put
     * clients on different nodes of the mesh.
     */
    suspend fun online(prefer: String? = null): ConnectionState.Online {
        if (prefer != null) for (c in manifests.candidates(true)) {
            if (c.label == prefer) stats.success(c.key, 1, clock.now()) else stats.failure(c.key, clock.now())
        }
        engine.start()
        return engine.connection.first { it is ConnectionState.Online } as ConnectionState.Online
    }

    fun close() { engine.stop(); scope.cancel(); db.close() }
}
