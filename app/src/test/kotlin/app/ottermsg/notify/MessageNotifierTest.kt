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
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.database.model.MessageKind
import app.ottermsg.core.database.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** D62 on Robolectric: what gets posted, when nothing is, and that a read cancels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ru") // plain Application: OtterApp would open the real (SQLCipher) graph
class MessageNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(app, OtterDatabase::class.java).allowMainThreadQueries().build()
    private val settings = Settings(db.settingsDao())
    private val active = ActiveConversation()
    private val visibility = AppVisibility()
    private val notifier = MessageNotifier(app, db, settings, active, visibility)
    private val shadowNm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val mama = ByteArray(16) { 1 }
    private var cid = 0L

    @Before fun setUp() = runBlocking {
        Channels.create(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db.contactDao().upsert(ContactEntity(mama, "mama", "Мама", ByteArray(32), ByteArray(33), verified = true,
            identityChanged = false, revoked = false, entryVersion = 1))
        cid = db.conversationDao().getOrCreateDirect(mama, "Мама", 1)
        for ((i, text) in listOf("Купи хлеб", "и молоко").withIndex()) {
            db.messageDao().insert(MessageEntity(messageId = ByteArray(16) { (i + 10).toByte() }, conversationId = cid, senderId = mama,
                outgoing = false, sentMs = 100L + i, receivedMs = 100L + i, body = text, status = MessageStatus.DELIVERED, kind = MessageKind.TEXT))
        }
    }

    @After fun tearDown() = db.close()

    private fun posted(): Notification? = shadowNm.getNotification(cid.toInt())

    @Test fun postsPrivateMessagingStyleWithActions() = runBlocking {
        notifier.onIncoming(cid)
        val n = posted()!!
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Otter", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Новое сообщение", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(2, n.actions.size)
        assertEquals(Channels.MESSAGES, n.channelId)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)!!
        assertEquals(listOf("Купи хлеб", "и молоко"), style.messages.map { it.text.toString() })
        assertEquals("Мама", style.messages[0].person!!.name.toString())
        assertNotNull(shadowNm.getNotification(MessageNotifier.SUMMARY_ID))
        assertNotNull(n.contentIntent)
    }

    @Test fun activeChatWhileVisibleIsSilent() = runBlocking {
        active.id = cid; visibility.visible = true
        notifier.onIncoming(cid)
        assertNull(posted())
        visibility.visible = false // same chat, app in the background: notify
        notifier.onIncoming(cid)
        assertNotNull(posted())
    }

    @Test fun readCancels() = runBlocking {
        notifier.onIncoming(cid)
        assertNotNull(posted())
        notifier.onRead(cid)
        assertNull(posted())
        assertNull(shadowNm.getNotification(MessageNotifier.SUMMARY_ID))
    }

    @Test fun disabledInAppPostsNothing() = runBlocking {
        settings.setNotificationsEnabled(false)
        notifier.onIncoming(cid)
        assertTrue(shadowNm.allNotifications.isEmpty())
    }

    @Test fun missingPermissionPostsNothing() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.onIncoming(cid)
        assertTrue(shadowNm.allNotifications.isEmpty())
    }
}
