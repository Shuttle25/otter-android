package app.ottermsg.core.data.push

import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.data.settings.Settings
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** The push provider's token (Firebase in the app module). */
interface PushTokenSource {
    /** A fresh token (re-enables automatic token creation), or null when push is unavailable. */
    suspend fun fetch(): String?
    /** Deletes the token at the provider and stops automatic re-creation. */
    suspend fun delete()
}

/**
 * The two switches of «Уведомления» (D94): they save the setting and tell the nodes at once when online.
 * There is no keep-alive connection: with Google wake-ups off the phone uses the 15-min battery saver (D61).
 */
@Singleton
class PushSwitches @Inject constructor(
    private val settings: Settings, private val registrar: PushRegistrar, private val online: OnlineApi,
    private val tokens: PushTokenSource,
) {
    /** «Показывать уведомления»: off also asks the nodes for call pushes only. */
    suspend fun setNotifications(on: Boolean) {
        settings.setNotificationsEnabled(on)
        registerNow()
    }

    /** «Пробуждение через Google»: off deletes the FCM token and withdraws it; the phone falls back to SAVER (D61). */
    suspend fun setGoogleWake(on: Boolean) {
        settings.setGoogleWake(on)
        try {
            if (on) tokens.fetch()?.let { settings.setPushToken(it) } else { tokens.delete(); settings.setPushToken("") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(Level.WARNING, "push token ${if (on) "fetch" else "delete"} failed", e) // fetched again at the next start
            if (!on) settings.setPushToken("")
        }
        registerNow()
    }

    private suspend fun registerNow() {
        val api = online.current() ?: return // sent at the next login (SyncEngine.runSession)
        try { registrar.registerIfNeeded(api) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { log.log(Level.WARNING, "push state not sent; next login", e) }
    }

    private companion object { val log: Logger = Logger.getLogger("PushSwitches") }
}
