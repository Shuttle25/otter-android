package app.ottermsg.core.data.push

import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.network.NodeApi
import app.ottermsg.proto.v1.PushToken
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Sends this device's push state as a signed PUSH_TOKEN record when it changed (D60, D94). It replicates to every node. */
@Singleton
class PushRegistrar @Inject constructor(
    private val accounts: AccountRepository, private val settings: Settings, private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun registerIfNeeded(api: NodeApi) = mutex.withLock {
        val sent = settings.pushTokenSent()
        val payload = PushToken.newBuilder().setDeviceId(1).setKind(PushToken.Kind.FCM)
        if (!settings.googleWake()) {
            if (sent == null || sent == WITHDRAWN) return@withLock // never registered, or already withdrawn
            payload.setWithdrawn(true)
        } else {
            val token = settings.pushToken()?.takeIf { it.isNotEmpty() } ?: return@withLock
            payload.setToken(token).setMessagesOff(!settings.notificationsEnabled())
        }
        val p = payload.build()
        val state = stateOf(p)
        if (state == sent) return@withLock
        val me = accounts.require()
        api.registerPush(RecordCodec.sign(RecordCodec.body(RecordType.PUSH_TOKEN, me.userId, p.toByteArray(),
            createdMs = clock.now()), me.accountKey))
        settings.setPushTokenSent(state)
    }

    companion object {
        /** The sent state of a withdrawn token; a plain token string is "token, notifications on" (pre-D94 installs). */
        const val WITHDRAWN = "withdrawn"
        fun stateOf(p: PushToken): String = when {
            p.withdrawn -> WITHDRAWN
            p.messagesOff -> "${p.token}|calls-only"
            else -> p.token
        }
    }
}
