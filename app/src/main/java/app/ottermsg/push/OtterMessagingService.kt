package app.ottermsg.push

import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.push.PushRegistrar
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.sync.SyncEngine
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FCM entry point (D59/D61): a new token is stored and registered; any message means "you have mail". */
@AndroidEntryPoint
class OtterMessagingService : FirebaseMessagingService() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var engine: SyncEngine
    @Inject lateinit var wake: WakeScheduler
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onNewToken(token: String) {
        scope.launch {
            settings.setPushToken(token)
            engine.current()?.let { api -> runCatching { registrar.registerIfNeeded(api) } }
        }
    }

    /** The payload is a constant (D59): any message means "you have mail". */
    override fun onMessageReceived(message: RemoteMessage) = wake.wakeNow(fromPush = true)
}
