package app.ottermsg.core.data.signal

import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.signal.PreKeyFactory
import app.ottermsg.proto.v1.PreKeys
import app.ottermsg.proto.v1.RecordType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Uploads fresh one-time prekeys owned by the connected node (on prekeys_low, or on login if a refill is pending). */
@Singleton
class PrekeyRefill @Inject constructor(
    private val accounts: AccountRepository, private val settings: Settings, private val signal: SignalService, private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun refill(api: NodeApi, count: Int = BATCH) = mutex.withLock {
        settings.setPrekeysLow(true) // cleared only after a successful upload
        val me = accounts.require()
        val node = api.nodeId()
        val first = settings.nextOneTimeId()
        val keys = signal.withSessions { store, _ -> PreKeyFactory.oneTime(store, first, count, node) }
        val payload = PreKeys.newBuilder().setDeviceId(1).setRegistrationId(me.registrationId).addAllOneTime(keys).build()
        val rec = RecordCodec.sign(RecordCodec.body(RecordType.PREKEYS, me.userId, payload.toByteArray(), createdMs = clock.now()), me.accountKey)
        settings.setNextOneTimeId(first + count) // never reuse ids, even if the upload fails
        api.uploadPrekeys(rec)
        settings.setPrekeysLow(false)
    }

    suspend fun refillIfPending(api: NodeApi) { if (settings.prekeysLow()) refill(api) }

    companion object { const val BATCH = 50 }
}
