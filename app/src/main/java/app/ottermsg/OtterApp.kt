package app.ottermsg

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import android.media.AudioManager
import app.ottermsg.core.data.calls.CallManager
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.push.PushMonitor
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.sync.ForegroundSync
import app.ottermsg.notify.Channels
import app.ottermsg.push.PushAvailability
import app.ottermsg.push.WakeScheduler
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OtterApp : Application(), Configuration.Provider {
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var foregroundSync: ForegroundSync
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: Settings
    @Inject lateinit var monitor: PushMonitor
    @Inject lateinit var wake: WakeScheduler
    @Inject lateinit var calls: dagger.Lazy<CallManager>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Channels.create(this)
        // Sync while visible, once an account exists (loaded at start, or right after registration).
        appScope.launch(Dispatchers.Main) {
            accounts.account.filterNotNull().first()
            ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundSync)
        }
        appScope.launch {
            val available = PushAvailability.check(this@OtterApp)
            settings.setPushAvailable(available)
            if (available && settings.googleWake())
                FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> appScope.launch { settings.setPushToken(t) } }
            else if (available) FirebaseMessaging.getInstance().isAutoInitEnabled = false // wake-ups off (D94)
            // Background jobs only make sense with an account; then FAST/SAVER decides whether the 15-min check runs (D61).
            if (accounts.load() == null) accounts.account.filterNotNull().first()
            // Another call (GSM or another VoIP app) in progress: an incoming Otter call is answered BUSY (D80).
            val am = getSystemService(AudioManager::class.java)
            val manager = calls.get()
            manager.systemBusy = {
                am.mode == AudioManager.MODE_IN_CALL ||
                    (am.mode == AudioManager.MODE_IN_COMMUNICATION && manager.state.value == CallState.Idle)
            }
            monitor.observeMode().distinctUntilChanged().collect { wake.apply(it) }
        }
    }
}
