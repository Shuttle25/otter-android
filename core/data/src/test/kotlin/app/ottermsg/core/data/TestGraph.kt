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
import app.ottermsg.core.data.media.GallerySaver
import app.ottermsg.core.data.media.IncomingPhotos
import app.ottermsg.core.data.media.MediaFiles
import app.ottermsg.core.data.media.MediaRepository
import app.ottermsg.core.data.media.MediaTransfers
import app.ottermsg.core.data.media.NetworkInfo
import app.ottermsg.core.data.media.PhotoLoader
import app.ottermsg.core.data.media.PhotoPrep
import app.ottermsg.core.data.profile.ProfileRepository
import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.signal.PrekeyRefill
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.key.KeyWrapper
import app.ottermsg.core.network.NodeApi
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
class TestGraph(
    val node: FakeNode, val clock: TestClock,
    val db: OtterDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java).build(),
    /** Media directory name; a restarted graph keeps it (same filesDir, like a real app restart). */
    private val graphId: String = java.util.UUID.randomUUID().toString(),
    /** Notification hooks (D62); tests record them. */
    val alerts: app.ottermsg.core.data.push.MessageAlerts = app.ottermsg.core.data.push.MessageAlerts.NONE,
    /** Call content from the receiver (D78); tests record it. */
    val calls: app.ottermsg.core.data.calls.CallInbox = app.ottermsg.core.data.calls.CallInbox.NONE,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(db.settingsDao())
    val accounts = AccountRepository(db.accountDao(), settings, SeedVault(XorWrapper))
    val signal = SignalService(accounts, db.signalKvDao(), db.contactDao(), scope)
    val directory = DirectorySync(accounts, db.contactDao(), db.conversationDao(), settings, db.accountDao(), db.manifestDao(), db.messageDao(), clock)
    val profile = ProfileRepository(accounts, db, settings, clock)
    val outbox = Outbox(db, accounts, signal, clock, GroupSender(db, signal, clock))
    val files = object : MediaFiles(ApplicationProvider.getApplicationContext()) {
        override val mediaDir get() = java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "media-$graphId").also { it.mkdirs() }
    }
    @Volatile var unmetered = true
    val photoLoader = PhotoLoader(db, files)
    val gallery = GallerySaver(ApplicationProvider.getApplicationContext(), photoLoader)
    val media = MediaTransfers(db, accounts, files, settings, NetworkInfo { unmetered }, gallery, outbox, clock)
    val incomingPhotos = IncomingPhotos(db, files, settings, media)
    val photos = MediaRepository(db, photoLoader, gallery, media, files)
    val applier = GroupApplier(db, accounts, directory, clock)
    val resets = app.ottermsg.core.data.messaging.SessionResets(db, signal, outbox, clock)
    val groupReceiver = GroupReceiver(db, accounts, signal, applier, outbox, clock, incomingPhotos, resets, alerts)
    val receiver = MailboxReceiver(db, accounts, signal, directory, outbox, clock, groupReceiver, incomingPhotos, resets, alerts, calls)
    val guard = app.ottermsg.core.data.backup.InstallGuard(db, accounts, settings, signal, resets, clock, directory)
    val backups = app.ottermsg.core.data.backup.BackupManager(ApplicationProvider.getApplicationContext(), db, accounts, settings,
        SeedVault(XorWrapper), { app.ottermsg.core.data.invite.OnlineApi { session } }, clock)
        .apply { derive = { pw, salt, _ -> app.ottermsg.core.crypto.BackupKeys.derive(pw, salt, mKib = 1024, t = 1, p = 1) } }
    val groups = GroupRepository(db, accounts, applier, outbox, clock)
    val groupRevocations = app.ottermsg.core.data.group.GroupRevocations(db, accounts, groups, settings)
    val messages = MessageRepository(db, accounts, outbox, clock, files, PhotoPrep(), media, alerts)
    val prekeys = PrekeyRefill(accounts, settings, signal, clock, db.manifestDao())
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
    val restore get() = app.ottermsg.core.data.backup.RestoreManager(db, connector, SeedVault(XorWrapper), accounts, settings, clock, allowPlaintext = true)
        .apply { derive = { pw, salt, _ -> app.ottermsg.core.crypto.BackupKeys.derive(pw, salt, mKib = 1024, t = 1, p = 1) } }
    /** The engine's own connections (start / acquire) reach [node] through this. */
    val transport = FakeNodeTransport(node)
    /** Candidates whose host is listed here reach another (fake) node; every other host reaches [transport]. */
    val routes = java.util.concurrent.ConcurrentHashMap<String, app.ottermsg.core.network.Transport>()
    private val router = object : app.ottermsg.core.network.Transport {
        override suspend fun connect(candidate: app.ottermsg.core.network.Candidate) =
            (routes[candidate.host] ?: transport).connect(candidate)
    }
    val manifests = app.ottermsg.core.data.sync.ManifestUpdater(db.manifestDao(), settings, clock)
    val pushMonitor = app.ottermsg.core.data.push.PushMonitor(settings, clock)
    val news = app.ottermsg.core.data.news.NewsRepository(db, manifests, clock, alerts)
    private val endpointStats = app.ottermsg.core.network.InMemoryEndpointStats()
    val engine = SyncEngine(endpointStats, app.ottermsg.core.network.NodeSelector(router, endpointStats, clock::now),
        accounts, settings, manifests, directory, receiver, outbox, prekeys, profile, media, scope, true, clock, guard, backups,
        news, groupRevocations, pushMonitor = pushMonitor)
    val foreground = app.ottermsg.core.data.sync.ForegroundSync({ engine.acquire(SyncEngine.FOREGROUND) },
        { engine.release(SyncEngine.FOREGROUND) }, scope)
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

    private var closeDb = true

    /** Simulates an app restart: this graph stops (database kept), a new graph opens on the same database. */
    suspend fun restart(): TestGraph {
        offline(); engine.stop(); scope.cancel(); closeDb = false
        return TestGraph(node, clock, db, graphId, alerts, calls).also { it.accounts.load() }
    }

    fun close() { offline(); engine.stop(); scope.cancel(); if (closeDb) db.close() }
}

suspend fun eventually(timeoutMs: Long = 15_000, what: String = "condition", cond: suspend () -> Boolean) {
    try {
        withTimeout(timeoutMs) { while (!cond()) delay(20) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for $what", e)
    }
}
