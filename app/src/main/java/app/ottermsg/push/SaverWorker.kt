package app.ottermsg.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.push.BackgroundSync
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** «Экономия батареи» (D61): the periodic 15-min check in SAVER mode. */
@HiltWorker
class SaverWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters, private val sync: BackgroundSync,
    private val accounts: AccountRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (accounts.load() == null) return Result.success() // no account (yet): nothing to check
        sync.drain(fromPush = false)
        return Result.success()
    }
}
