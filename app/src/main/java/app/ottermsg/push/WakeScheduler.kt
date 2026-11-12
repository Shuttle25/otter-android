package app.ottermsg.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.ottermsg.core.data.push.PushMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background sync jobs (D61): one expedited wake per push/action, and the 15-min battery saver in SAVER mode. */
@Singleton
class WakeScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    /** A push arrived (or a notification action needs the network): one expedited drain. */
    fun wakeNow(fromPush: Boolean = false) {
        wm.enqueueUniqueWork(WAKE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<WakeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(WakeWorker.FROM_PUSH to fromPush)).build())
    }

    /** D61: the 15-min battery saver runs only in SAVER mode. */
    fun apply(mode: PushMode) {
        if (mode == PushMode.SAVER) {
            wm.enqueueUniquePeriodicWork(SAVER, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SaverWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        } else wm.cancelUniqueWork(SAVER)
    }

    companion object { const val WAKE = "wake"; const val SAVER = "saver" }
}
