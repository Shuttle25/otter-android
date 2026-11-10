package app.ottermsg

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.sync.ForegroundSync
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OtterApp : Application() {
    @Inject lateinit var accounts: AccountRepository
    @Inject lateinit var foregroundSync: ForegroundSync
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Sync while visible, once an account exists (loaded at start, or right after registration).
        appScope.launch(Dispatchers.Main) {
            accounts.account.filterNotNull().first()
            ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundSync)
        }
    }
}
