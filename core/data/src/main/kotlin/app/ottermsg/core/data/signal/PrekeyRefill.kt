package app.ottermsg.core.data.signal

import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.signal.PreKeyFactory
import app.ottermsg.core.signal.hexToBytes
import app.ottermsg.core.signal.toHex
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

    /**
     * Uploads [count] one-time keys owned by [owner] (the node that reported it is low; each node only hands out its
     * own keys). Null/empty owner = the connected node. Any node accepts the upload; replication spreads it.
     */
    suspend fun refill(api: NodeApi, owner: ByteArray? = null, count: Int = BATCH) = mutex.withLock {
        val node = owner?.takeIf { it.isNotEmpty() } ?: api.nodeId()
        val key = node.toHex()
        settings.setPendingRefills(settings.pendingRefills() + key) // cleared only after a successful upload
        val me = accounts.require()
        val first = settings.nextOneTimeId()
        val keys = signal.withSessions { store, _ -> PreKeyFactory.oneTime(store, first, count, node) }
        val payload = PreKeys.newBuilder().setDeviceId(1).setRegistrationId(me.registrationId).addAllOneTime(keys).build()
        val rec = RecordCodec.sign(RecordCodec.body(RecordType.PREKEYS, me.userId, payload.toByteArray(), createdMs = clock.now()), me.accountKey)
        settings.setNextOneTimeId(first + count) // never reuse ids, even if the upload fails
        api.uploadPrekeys(rec)
        settings.setPendingRefills(settings.pendingRefills() - key)
    }

    /** On login: retry refills that did not complete (per owner), plus the M1 single flag. */
    suspend fun refillIfPending(api: NodeApi) {
        if (settings.prekeysLow()) { refill(api, null); settings.setPrekeysLow(false) }
        for (owner in settings.pendingRefills()) refill(api, owner.hexToBytes())
    }

    companion object { const val BATCH = 50 }
}
