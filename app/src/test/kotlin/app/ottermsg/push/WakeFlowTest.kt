package app.ottermsg.push

import app.ottermsg.core.data.security.AppLock
import app.ottermsg.core.data.security.FakeLockStore
import app.ottermsg.core.data.security.PlainHasher
import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.ottermsg.core.data.FakeNode
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.data.TestGraph
import app.ottermsg.core.data.eventually
import app.ottermsg.core.data.push.ActiveConversation
import app.ottermsg.core.data.push.AppVisibility
import app.ottermsg.core.data.push.BackgroundSync
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.notify.Channels
import app.ottermsg.notify.MessageNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Push → wake → notify" minus Google: a WakeWorker run drains bob's mailbox, notifies and goes offline again (D61/D62). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // plain Application: OtterApp would open the real (SQLCipher) graph
class WakeFlowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val clock = TestClock()
    private val node = FakeNode(clock::now)
    private val alice = TestGraph(node, clock)
    private val bobDb = Room.inMemoryDatabaseBuilder(app, OtterDatabase::class.java).build()
    private val notifier = MessageNotifier(app, bobDb, Settings(bobDb.settingsDao()), ActiveConversation(), AppVisibility(),
        AppLock(FakeLockStore(), { 0L }, PlainHasher))
    private val bob = TestGraph(node, clock, db = bobDb, alerts = notifier)
    /** TestClock only ticks per call; the worker's 10 s idle wait needs it to run (10x faster than real time). */
    private val ticker = CoroutineScope(SupervisorJob() + Dispatchers.Default).apply {
        launch { while (true) { delay(10); clock.advance(100) } }
    }

    @Before fun setUp() {
        Channels.create(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() { ticker.cancel(); alice.close(); bob.close() }

    @Test fun wakeWorkerDrainsNotifiesAndGoesOffline() = runBlocking {
        alice.register(node.bootstrapLink(), "Алиса", "alice").getOrThrow()
        alice.online()
        bob.register(alice.invites.create().link, "Боб", "bob").getOrThrow()
        eventually(what = "alice knows bob") { alice.db.contactDao().get(bob.me.userId) != null }
        // bob is offline (no engine running): the message waits in his mailbox
        alice.messages.sendText(alice.messages.conversationWith(bob.me.userId), "Купи хлеб")
        eventually(what = "queued for bob") { node.mailbox(bob.me.userId).isNotEmpty() }

        val sync = BackgroundSync(bob.engine, bob.pushMonitor, bob.clock)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == WakeWorker::class.java.name) WakeWorker(appContext, workerParameters, sync, bob.accounts) else null
        }
        val worker = TestListenableWorkerBuilder<WakeWorker>(app)
            .setInputData(workDataOf(WakeWorker.FROM_PUSH to true)).setWorkerFactory(factory).build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        val msg = bob.db.messageDao().all().single { !it.outgoing }
        assertEquals("Купи хлеб", msg.body)
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertNotNull("notification for the chat", nm.getNotification(msg.conversationId.toInt()))
        assertNotNull(nm.getNotification(MessageNotifier.SUMMARY_ID))
        assertTrue(bob.engine.connection.value is ConnectionState.Offline)
        assertTrue(bob.settings.pushLastWakeMs() > 0)
        eventually(what = "acked") { node.mailbox(bob.me.userId).isEmpty() }
    }
}
