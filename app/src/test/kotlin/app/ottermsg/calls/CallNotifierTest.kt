package app.ottermsg.calls

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.calls.CallId
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import app.ottermsg.notify.Channels
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

/** D80 on Robolectric: ringing, missed-call and release notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ru") // plain Application: OtterApp would open the real graph
class CallNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notifier = CallNotifier(app)
    private val shadowNm get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val mama = Peer.of(ByteArray(16) { 1 }, "Мама")

    @Before fun setUp() {
        Channels.create(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun incoming(): Notification? {
        notifier.incoming(CallState.Incoming(CallId("ab".repeat(16)), mama, "sdp", 0))
        return shadowNm.getNotification(CallNotifier.INCOMING_ID)
    }

    @Test fun incomingRingsFullScreenOnTheCallsChannel() {
        val n = incoming()!!
        assertEquals(Channels.CALLS, n.channelId)
        assertEquals(NotificationCompat.CATEGORY_CALL, n.category)
        assertNotNull(n.fullScreenIntent)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(2, n.actions.size) // Decline + Accept
        // the call service start was attempted
        assertEquals(CallService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }

    @Test fun missedCallHasTheCallBackAction() {
        notifier.missed(mama, 42)
        val n = shadowNm.getNotification(CallNotifier.MISSED_BASE + 42)
        assertNotNull(n)
        assertEquals(Channels.MISSED_CALLS, n.channelId)
        assertEquals(NotificationCompat.CATEGORY_MISSED_CALL, n.category)
        assertEquals("Пропущенный звонок", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Мама", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Перезвонить"), n.actions.map { it.title.toString() })
        assertNotNull(n.contentIntent)
    }

    @Test fun releasedCancelsTheRinging() {
        assertNotNull(incoming())
        notifier.released()
        assertNull(shadowNm.getNotification(CallNotifier.INCOMING_ID))
    }

    @Test fun channelsExist() {
        val nm = app.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Channels.CALLS).importance)
        assertNotNull(nm.getNotificationChannel(Channels.CALLS).sound)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Channels.MISSED_CALLS).importance)
    }
}
