package app.ottermsg.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.awaitCancellation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import app.ottermsg.core.data.push.PushMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** D61: the battery saver runs only in SAVER mode; wake-ups are one unique expedited job. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class) // plain Application: OtterApp would open the real (SQLCipher) graph
class WakeSchedulerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var wm: WorkManager
    private lateinit var scheduler: WakeScheduler

    @Before fun setUp() {
        // The test WorkManager runs unconstrained work at once: a worker that never finishes keeps the first wake RUNNING.
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result = awaitCancellation()
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        wm = WorkManager.getInstance(context)
        scheduler = WakeScheduler(context)
    }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    @Test fun saverOnlyInSaverMode() {
        scheduler.apply(PushMode.SAVER)
        scheduler.apply(PushMode.SAVER) // KEEP: still one
        val saver = infos(WakeScheduler.SAVER)
        assertEquals(1, saver.size)
        assertEquals(WorkInfo.State.ENQUEUED, saver[0].state)
        assertEquals(NetworkType.CONNECTED, saver[0].constraints.requiredNetworkType)
        val periodicity = saver[0].periodicityInfo!!
        assertEquals(TimeUnit.MINUTES.toMillis(15), periodicity.repeatIntervalMillis)

        scheduler.apply(PushMode.FAST)
        assertEquals(WorkInfo.State.CANCELLED, infos(WakeScheduler.SAVER).single().state)
    }

    @Test fun wakeIsOneUniqueJobKeepingTheFirst() {
        scheduler.wakeNow(fromPush = true)
        scheduler.wakeNow(fromPush = false)
        val wake = infos(WakeScheduler.WAKE)
        assertEquals(1, wake.size)
        assertTrue(wake[0].state == WorkInfo.State.ENQUEUED || wake[0].state == WorkInfo.State.RUNNING)
        // WorkInfo hides the input; the stored spec shows the first request was kept (from_push = true) and is expedited.
        val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(wake[0].id.toString())!!
        assertTrue(spec.input.getBoolean(WakeWorker.FROM_PUSH, false))
        assertTrue(spec.expedited)
        assertEquals(WakeWorker::class.java.name, spec.workerClassName)
    }
}
