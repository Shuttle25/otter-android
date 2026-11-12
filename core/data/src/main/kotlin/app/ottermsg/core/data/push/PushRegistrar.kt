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

/** Sends this device's FCM token as a signed PUSH_TOKEN record when it changed (D60). It replicates to every node. */
@Singleton
class PushRegistrar @Inject constructor(
    private val accounts: AccountRepository, private val settings: Settings, private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun registerIfNeeded(api: NodeApi) = mutex.withLock {
        val token = settings.pushToken()?.takeIf { it.isNotEmpty() } ?: return@withLock
        if (token == settings.pushTokenSent()) return@withLock
        val me = accounts.require()
        val payload = PushToken.newBuilder().setDeviceId(1).setKind(PushToken.Kind.FCM).setToken(token).build()
        api.registerPush(RecordCodec.sign(RecordCodec.body(RecordType.PUSH_TOKEN, me.userId, payload.toByteArray(),
            createdMs = clock.now()), me.accountKey))
        settings.setPushTokenSent(token)
    }
}
