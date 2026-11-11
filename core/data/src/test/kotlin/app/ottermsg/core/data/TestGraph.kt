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
import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.signal.PrekeyRefill
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.key.KeyWrapper
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** Strictly increasing fake time shared by all graphs of a test. */
class TestClock(start: Long = 1_800_000_000_000) : Clock {
    private val t = AtomicLong(start)
    override fun now(): Long = t.incrementAndGet()
    fun advance(ms: Long) { t.addAndGet(ms) }
}

object XorWrapper : KeyWrapper {
    override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun unwrap(wrapped: ByteArray) = wrap(wrapped)
}

/** One client wired by hand, as Hilt would, over an in-memory database and a [FakeNode]. */
class TestGraph(val node: FakeNode, val clock: TestClock) {
    val db: OtterDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(db.settingsDao())
    val accounts = AccountRepository(db.accountDao(), settings, SeedVault(XorWrapper))
    val signal = SignalService(accounts, db.signalKvDao(), db.contactDao(), scope)
    val directory = DirectorySync(accounts, db.contactDao(), db.conversationDao())
    val outbox = Outbox(db, accounts, signal, clock)
    val receiver = MailboxReceiver(db, accounts, signal, directory, outbox, clock)
    val messages = MessageRepository(db, accounts, outbox, clock)
    val prekeys = PrekeyRefill(accounts, settings, signal, clock)
    @Volatile var session: FakeNode.Session? = null
    private var sessionScope: CoroutineScope? = null
    val invites = InviteRepository(accounts, settings, db.manifestDao(), { session }, clock)
    var connector: NodeConnector = object : NodeConnector {
        override suspend fun <T> withNode(candidates: List<app.ottermsg.core.network.Candidate>, block: suspend (NodeApi) -> T): T {
            if (candidates.isEmpty()) throw IOException("no endpoints")
            val s = node.connect()
            try { return block(s) } finally { s.close() }
        }
    }
    val registration get() = Registration(db, connector, SeedVault(XorWrapper), accounts, settings, clock, allowPlaintext = true)
    private val noTransport = object : Transport { override suspend fun connect(candidate: app.ottermsg.core.network.Candidate) = throw IOException("unused") }
    val engine = SyncEngine(noTransport, accounts, settings, db.manifestDao(), directory, receiver, outbox, prekeys, scope, true, clock)
    val seed = ByteArray(32).also(SecureRandom()::nextBytes)
    val me get() = accounts.require()

    suspend fun register(link: String, name: String, username: String): Result<Unit> =
        registration.register(link, name, username, seed)

    /** Opens a session like SyncEngine does for a real connection. */
    suspend fun online() {
        offline()
        val s = node.connect()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session = s; sessionScope = sc
        engine.runSession(s, sc)
    }

    fun offline() {
        sessionScope?.cancel(); sessionScope = null
        session?.close(); session = null
    }

    fun close() { offline(); scope.cancel(); db.close() }
}

suspend fun eventually(timeoutMs: Long = 15_000, what: String = "condition", cond: suspend () -> Boolean) {
    try {
        withTimeout(timeoutMs) { while (!cond()) delay(20) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for $what", e)
    }
}
