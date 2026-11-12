package app.ottermsg.core.data.push

import javax.inject.Inject
import javax.inject.Singleton

/** Notification hooks the data layer calls (D62). The app module binds the Android implementation. */
interface MessageAlerts {
    /** A visible incoming message (text or photo) was stored in [conversationId]. */
    suspend fun onIncoming(conversationId: Long)
    /** [conversationId] was read on this phone. */
    fun onRead(conversationId: Long)

    companion object {
        val NONE = object : MessageAlerts {
            override suspend fun onIncoming(conversationId: Long) {}
            override fun onRead(conversationId: Long) {}
        }
    }
}

/** The conversation currently on screen (set by the conversation screen while resumed). */
@Singleton
class ActiveConversation @Inject constructor() {
    @Volatile var id: Long? = null
}

/** Whether the app is visible (ForegroundSync keeps it current). Separate from ForegroundSync to avoid a DI cycle:
 *  SyncEngine → receivers → MessageAlerts → notifier must not depend on the engine. */
@Singleton
class AppVisibility @Inject constructor() {
    @Volatile var visible: Boolean = false
}
