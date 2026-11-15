package app.ottermsg.push

import android.content.Context
import app.ottermsg.core.data.push.PushTokenSource
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The FCM token for «Пробуждение через Google» (D94); without Google services or Firebase nothing to fetch or delete. */
class FirebasePushTokens @Inject constructor(@param:ApplicationContext private val ctx: Context) : PushTokenSource {
    override suspend fun fetch(): String? {
        if (!PushAvailability.check(ctx)) return null
        val m = FirebaseMessaging.getInstance()
        m.isAutoInitEnabled = true
        return m.token.await()
    }

    override suspend fun delete() {
        if (!PushAvailability.check(ctx)) return
        val m = FirebaseMessaging.getInstance()
        m.isAutoInitEnabled = false // no new token at the next start
        m.deleteToken().await()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnCompleteListener { t ->
            if (t.isSuccessful) c.resume(t.result) else c.resumeWithException(t.exception ?: IOException("FCM task failed"))
        }
    }
}
