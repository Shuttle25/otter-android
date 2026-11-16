package app.ottermsg.core.data.signal

import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.dao.ManifestDao
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
    private val manifests: ManifestDao,
) {
    private val mutex = Mutex()

    /**
     * Uploads [count] one-time keys owned by [owner] (the node that reported it is low; each node only hands out its
     * own keys). Null/empty owner = the connected node. Any node accepts the upload; replication spreads it.
     * D108: only for owners in the verified manifest (or the connected node), at most once per owner per
     * [MIN_GAP_MS] and [MAX_PER_DAY] times per 24 h, so a hostile node cannot make the phone mint keys without end.
     */
    suspend fun refill(api: NodeApi, owner: ByteArray? = null, count: Int = BATCH) = mutex.withLock {
        val node = owner?.takeIf { it.isNotEmpty() } ?: api.nodeId()
        val key = node.toHex()
        if (key !in knownOwners(api)) { log.warning("prekeys_low for a node outside the manifest ignored"); return@withLock }
        if (!takeSlot(key)) { log.info("prekey refill for $key skipped (rate)"); return@withLock }
        settings.setPendingRefills(settings.pendingRefills() + key) // cleared only after a successful upload
        val me = accounts.require()
        val first = settings.nextOneTimeId()
        val keys = signal.withSessions { store, _ -> PreKeyFactory.oneTime(store, first, count, node) }
        val payload = PreKeys.newBuilder().setDeviceId(1).setRegistrationId(me.registrationId).setInstalledMs(settings.installedMs())
            .addAllOneTime(keys).build()
        val rec = me.sign(RecordCodec.body(RecordType.PREKEYS, me.userId, payload.toByteArray(), createdMs = clock.now()))
        settings.setNextOneTimeId(first + count) // never reuse ids, even if the upload fails
        api.uploadPrekeys(rec)
        settings.setPendingRefills(settings.pendingRefills() - key)
    }

    /** On login: retry refills that did not complete (per owner), plus the M1 single flag. */
    suspend fun refillIfPending(api: NodeApi) {
        if (settings.prekeysLow()) { refill(api, null); settings.setPrekeysLow(false) }
        val known = knownOwners(api)
        val pending = settings.pendingRefills()
        if (!known.containsAll(pending)) settings.setPendingRefills(pending intersect known)
        for (owner in pending intersect known) refill(api, owner.hexToBytes())
    }

    /** Owner nodes we upload keys for: the verified manifest's nodes and the node we are connected to. */
    private suspend fun knownOwners(api: NodeApi): Set<String> {
        val m = manifests.latest()?.let { runCatching { ManifestVerifier.verify(it.raw, settings.networkPub(), 0, 0) }.getOrNull() }
        return (m?.nodesList?.map { it.nodeId.toByteArray().toHex() } ?: emptyList()).toSet() + api.nodeId().toHex()
    }

    /** At most one refill per owner per [MIN_GAP_MS] and [MAX_PER_DAY] per 24 h; records the refill when allowed. */
    private suspend fun takeSlot(owner: String): Boolean {
        val now = clock.now()
        val all = settings.refillTimes().mapValues { (_, v) -> v.filter { now - it < DAY_MS } }.filterValues { it.isNotEmpty() }
        val mine = all[owner].orEmpty()
        if (mine.size >= MAX_PER_DAY || (mine.isNotEmpty() && now - mine.last() < MIN_GAP_MS)) {
            settings.setRefillTimes(all)
            return false
        }
        settings.setRefillTimes(all + (owner to mine + now))
        return true
    }

    companion object {
        const val BATCH = 50
        const val MAX_PER_DAY = 6
        const val MIN_GAP_MS = 5 * 60_000L
        private const val DAY_MS = 24 * 3_600_000L
        private val log = java.util.logging.Logger.getLogger("PrekeyRefill")
    }
}
