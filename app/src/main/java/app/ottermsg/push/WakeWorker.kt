package app.ottermsg.push

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.ottermsg.R
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.push.BackgroundSync
import app.ottermsg.notify.Channels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** One expedited background drain (push or notification action). On API < 31 it runs as a dataSync foreground service. */
@HiltWorker
class WakeWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters, private val sync: BackgroundSync,
    private val accounts: AccountRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A push or notification action may start the process with no screen: the engine needs the account loaded.
        if (accounts.load() == null) return Result.success()
        sync.drain(fromPush = inputData.getBoolean(FROM_PUSH, false))
        return Result.success() // a failed drain is retried by the next push / saver run / app start
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Channels.SYNC).setSmallIcon(R.drawable.ic_stat_otter)
            .setContentText(applicationContext.getString(R.string.notif_sync_text)).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    companion object { const val FROM_PUSH = "from_push"; const val NOTIF_ID = Int.MAX_VALUE - 2 }
}
