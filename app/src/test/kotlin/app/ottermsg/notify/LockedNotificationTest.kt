package app.ottermsg.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.push.ActiveConversation
import app.ottermsg.core.data.push.AppVisibility
import app.ottermsg.core.data.security.AppLock
import app.ottermsg.core.data.security.FakeLockStore
import app.ottermsg.core.data.security.PlainHasher
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** D121: while the app lock is on, a message notification names nobody, shows no text and offers no «Ответить». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ru")
class LockedNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(app, OtterDatabase::class.java).allowMainThreadQueries().build()
    private val settings = Settings(db.settingsDao())
    private val store = FakeLockStore()
    private val shadowNm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val mama = ByteArray(16) { 1 }
    private var cid = 0L

    private fun notifier(lock: AppLock) = MessageNotifier(app, db, settings, ActiveConversation(), AppVisibility(), lock)
    private fun lock() = AppLock(store, { 1_000_000L }, PlainHasher)

    @Before fun setUp() = runBlocking {
        Channels.create(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db.contactDao().upsert(ContactEntity(mama, "mama", "Мама", ByteArray(32), ByteArray(33), verified = true,
            identityChanged = false, revoked = false, entryVersion = 1))
        cid = db.conversationDao().getOrCreateDirect(mama, "Мама", 1)
        db.messageDao().insert(MessageEntity(messageId = ByteArray(16) { 9 }, conversationId = cid, senderId = mama,
            outgoing = false, sentMs = 100L, receivedMs = 100L, body = "секрет", status = MessageStatus.DELIVERED, kind = MessageKind.TEXT))
        Unit
    }

    @After fun tearDown() = db.close()

    private fun allText(n: Notification): String = buildString {
        for (k in n.extras.keySet()) append(n.extras.get(k)).append('|')
        n.publicVersion?.let { p -> for (k in p.extras.keySet()) append(p.extras.get(k)).append('|') }
        n.actions?.forEach { append(it.title).append('|') }
    }

    private fun assertLockedForm(n: Notification) {
        assertEquals("Otter", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Новое сообщение", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull("no MessagingStyle", n.extras.get(Notification.EXTRA_MESSAGES))
        assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n))
        assertTrue("no actions at all (no «Ответить»)", n.actions.isNullOrEmpty())
        val all = allText(n)
        assertFalse(all, all.contains("секрет"))
        assertFalse(all, all.contains("Мама"))
        assertTrue("still opens the app (behind the lock)", n.contentIntent != null)
    }

    @Test fun lockOnHidesTextNamesAndReply() = runBlocking {
        val l = lock(); l.init(); l.enable("1234".toCharArray(), biometric = false)
        notifier(l).onIncoming(cid)
        assertLockedForm(shadowNm.getNotification(cid.toInt())!!)
        assertTrue("nothing else posted (summary lists no names either)", shadowNm.allNotifications.all { it.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains("секрет") != true })
    }

    @Test fun coldStartByPushWaitsForTheLockState() = runBlocking {
        store.hash = PlainHasher.hash("1234".toCharArray()) // lock on, saved earlier
        val l = lock() // a new process started by Firebase: init() has not run yet
        assertFalse(l.initialized.value)
        notifier(l).onIncoming(cid)
        assertLockedForm(shadowNm.getNotification(cid.toInt())!!)
        assertTrue(l.initialized.value)
    }

    @Test fun lockOffShowsTheTextWithReply() = runBlocking {
        val l = lock() // off; not initialized either: the notifier initializes it
        notifier(l).onIncoming(cid)
        val n = shadowNm.getNotification(cid.toInt())!!
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertEquals(listOf("секрет"), style.messages.map { it.text.toString() })
        assertTrue(n.actions.any { it.remoteInputs?.isNotEmpty() == true })
    }

    @Test fun turningTheLockOnHidesNotificationsAlreadyShown() = runBlocking {
        val l = lock(); l.init()
        val notifier = notifier(l)
        notifier.onIncoming(cid)
        assertTrue(shadowNm.getNotification(cid.toInt()) != null)
        l.enable("1234".toCharArray(), biometric = false)
        notifier.hideAll()
        assertNull(shadowNm.getNotification(cid.toInt()))
        assertNull(shadowNm.getNotification(MessageNotifier.SUMMARY_ID))
    }
}
