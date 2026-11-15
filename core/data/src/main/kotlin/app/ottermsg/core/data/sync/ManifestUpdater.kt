package app.ottermsg.core.data.sync

import app.ottermsg.core.crypto.ManifestException
import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.dao.ManifestDao
import app.ottermsg.core.database.entity.ManifestEntity
import app.ottermsg.core.network.Candidate
import app.ottermsg.core.network.Endpoints
import app.ottermsg.core.network.NodeApi
import app.ottermsg.proto.v1.Manifest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** Ed25519 public keys allowed to sign announcements right now (D65). */
interface NewsKeys {
    suspend fun newsKeys(): List<ByteArray>
}

/** Follows the admin's manifest updates (docs/05): verify, store, and derive connection candidates. */
@Singleton
class ManifestUpdater @Inject constructor(
    private val manifests: ManifestDao, private val settings: Settings, private val clock: Clock,
) : NewsKeys {
    enum class Result { IGNORED, STORED, STORED_NODE_REMOVED }

    private val mutex = Mutex()

    /** Verifies [raw] (a serialized MANIFEST record) and stores it if newer. [connectedNode] = node we are on. */
    suspend fun offer(raw: ByteArray, connectedNode: ByteArray?): Result = mutex.withLock {
        val current = manifests.latest()?.version ?: 0
        val m = try {
            ManifestVerifier.verify(raw, settings.networkPub(), current)
        } catch (e: ManifestException) {
            log.info("manifest ignored: ${e.message}")
            return@withLock Result.IGNORED
        }
        manifests.insert(ManifestEntity(m.version, raw, receivedMs = clock.now()))
        val removed = connectedNode != null && m.nodesList.none { it.nodeId.toByteArray().contentEquals(connectedNode) }
        if (removed) Result.STORED_NODE_REMOVED else Result.STORED
    }

    /** After login: fetch the node's manifest if its hello announces a newer version than ours. */
    suspend fun checkAfterLogin(api: NodeApi): Result {
        val hello = api.hello()
        val stored = manifests.latest()?.version ?: 0
        if (hello.manifestVersion <= stored) return Result.IGNORED
        return offer(api.manifest().toByteArray(), hello.nodeId.toByteArray())
    }

    /**
     * Latest manifest's endpoints, plus — for [FALLBACK_MS] after an update — the previous manifest's endpoints that are
     * not in the latest one, at lower priority, but only for a node the latest manifest still lists and only with pins
     * it still gives that node (C16, D92): a removed node or a withdrawn pin stops working at once.
     */
    suspend fun candidates(allowPlaintext: Boolean): List<Candidate> {
        val two = manifests.latestTwo()
        val latest = two.firstOrNull() ?: return emptyList()
        val networkPub = settings.networkPub()
        val newest = parse(latest.raw, networkPub) ?: return emptyList()
        val current = Endpoints.candidates(newest, allowPlaintext)
        val previous = two.getOrNull(1) ?: return current
        if (clock.now() - latest.receivedMs >= FALLBACK_MS) return current
        val old = parse(previous.raw, networkPub) ?: return current
        val pinsByNode: Map<String, Set<String>> = newest.nodesList.associate { n ->
            n.nodeId.toByteArray().hex() to n.endpointsList.flatMap { e -> e.tlsSpkiPinsList.map { it.toByteArray().hex() } }.toSet()
        }
        val known = current.map { it.key }.toSet()
        val fallback = Endpoints.candidates(old, allowPlaintext, priorityOffset = FALLBACK_PRIORITY).filter { c ->
            val allowed = pinsByNode[c.nodeId.hex()] ?: return@filter false
            c.key !in known && c.pins.all { it.hex() in allowed }
        }
        return current + fallback
    }

    /** News keys of the latest stored manifest (D65); empty if there is none. */
    override suspend fun newsKeys(): List<ByteArray> {
        val latest = manifests.latest() ?: return emptyList()
        return parse(latest.raw, settings.networkPub())?.newsKeysList?.map { it.toByteArray() } ?: emptyList()
    }

    private fun parse(raw: ByteArray, networkPub: ByteArray): Manifest? =
        try { ManifestVerifier.verify(raw, networkPub, 0) } catch (e: ManifestException) { null }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val FALLBACK_MS = 7 * 86_400_000L
        const val FALLBACK_PRIORITY = 1_000_000
        private val log = Logger.getLogger("ManifestUpdater")
    }
}
