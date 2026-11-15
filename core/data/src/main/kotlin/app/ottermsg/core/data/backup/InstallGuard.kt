package app.ottermsg.core.data.backup

import app.ottermsg.core.crypto.ManifestVerifier
import app.ottermsg.core.crypto.RecordCodec
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.directory.DirectorySync
import app.ottermsg.core.data.messaging.SessionResets
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.data.signal.SignalService
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.RpcException
import app.ottermsg.core.signal.PreKeyFactory
import app.ottermsg.proto.v1.Install
import app.ottermsg.proto.v1.LoginResponse
import app.ottermsg.proto.v1.PreKeys
import app.ottermsg.proto.v1.Record
import app.ottermsg.proto.v1.RecordBody
import app.ottermsg.proto.v1.RecordType
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/** Runs right after every login (D52, C14): finishes a restore, or notices that another phone replaced this one. */
@Singleton
class InstallGuard @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val settings: Settings,
    private val signal: SignalService, private val resets: SessionResets, private val clock: Clock,
    private val directory: DirectorySync,
) {
    private val log = Logger.getLogger("InstallGuard")

    /** False when this install is superseded: the caller must stop syncing. */
    suspend fun afterLogin(api: NodeApi, resp: LoginResponse): Boolean {
        if (settings.restorePending()) { finishRestore(api); return true }
        val me = accounts.require()
        val signed = if (resp.hasInstall()) verified(resp.install, me.userId, me.accountKey.publicKey) else null
        if (signed != null && isNewer(signed, settings.installedMs(), me.registrationId)) {
            settings.setSuperseded(true)
            return false
        }
        if (resp.hasInstall() && signed == null) log.warning("INSTALL in the login answer does not verify: ignored (C14)")
        if (resp.installedMs > settings.installedMs()) log.warning("unsigned newer installed_ms from the node: ignored (C14)")
        if (!settings.installPublished()) publishInstall(api)
        return true
    }

    /** The INSTALL in [rec] if it is ours and signed by our account key; else null. */
    private fun verified(rec: Record, userId: ByteArray, accountPub: ByteArray): Install? = runCatching {
        val body = RecordBody.parseFrom(rec.body)
        if (body.type != RecordType.INSTALL || !body.author.toByteArray().contentEquals(userId) || !RecordCodec.verify(rec, accountPub)) null
        else Install.parseFrom(body.payload)
    }.getOrNull()

    /** Same order as the nodes: later install time, then the higher registration id. */
    private fun isNewer(i: Install, installedMs: Long, registrationId: Int) =
        i.installedMs > installedMs || (i.installedMs == installedMs && i.registrationId > registrationId)

    /** Signs this phone's install. A node older than 0.9.0 answers INVALID: tried again after the next login. */
    private suspend fun publishInstall(api: NodeApi) {
        val me = accounts.require()
        val payload = Install.newBuilder().setRegistrationId(me.registrationId).setInstalledMs(settings.installedMs()).build()
        try {
            api.publishInstall(RecordCodec.sign(RecordCodec.body(RecordType.INSTALL, me.userId, payload.toByteArray(),
                createdMs = clock.now()), me.accountKey))
            settings.setInstallPublished(true)
        } catch (e: RpcException) {
            log.warning("install.publish refused: ${e.status}")
        }
    }

    /** INSTALL first (every node raises the install), then fresh prekeys (replacing the old phone's on every node),
     *  then a reset to every contact. */
    private suspend fun finishRestore(api: NodeApi) {
        publishInstall(api)
        val me = accounts.require()
        val manifest = ManifestVerifier.verify(checkNotNull(db.manifestDao().latest()) { "no manifest" }.raw, settings.networkPub(), 0)
        val now = clock.now()
        var next = 1
        val payload = signal.withSessions { store, _ ->
            val signed = PreKeyFactory.signed(store, 1, now)
            val kyber = PreKeyFactory.kyberLastResort(store, 1, now)
            val oneTime = manifest.nodesList.flatMap { n ->
                PreKeyFactory.oneTime(store, next, Registration.ONE_TIME_PER_NODE, n.nodeId.toByteArray()).also { next += Registration.ONE_TIME_PER_NODE }
            }
            PreKeys.newBuilder().setDeviceId(1).setRegistrationId(me.registrationId).setSignedPrekey(signed).setKyberPrekey(kyber)
                .addAllOneTime(oneTime)
        }.setInstalledMs(settings.installedMs()).build()
        settings.setNextOneTimeId(next)
        api.uploadPrekeys(RecordCodec.sign(RecordCodec.body(RecordType.PREKEYS, me.userId, payload.toByteArray(), createdMs = now), me.accountKey))
        settings.setRestorePending(false)
        directory.sync(api) // a words-only restore has no contacts yet: everyone must get the reset
        resets.sendToAll()
    }
}
