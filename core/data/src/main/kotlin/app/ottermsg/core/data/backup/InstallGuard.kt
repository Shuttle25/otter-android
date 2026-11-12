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
import app.ottermsg.core.signal.PreKeyFactory
import app.ottermsg.proto.v1.LoginResponse
import app.ottermsg.proto.v1.PreKeys
import app.ottermsg.proto.v1.RecordType
import javax.inject.Inject
import javax.inject.Singleton

/** Runs right after every login (D52): finishes a restore, or notices that another phone replaced this one. */
@Singleton
class InstallGuard @Inject constructor(
    private val db: OtterDatabase, private val accounts: AccountRepository, private val settings: Settings,
    private val signal: SignalService, private val resets: SessionResets, private val clock: Clock,
    private val directory: DirectorySync,
) {
    /** False when this install is superseded: the caller must stop syncing. */
    suspend fun afterLogin(api: NodeApi, resp: LoginResponse): Boolean {
        if (settings.restorePending()) { finishRestore(api); return true }
        if (resp.installedMs > settings.installedMs()) { settings.setSuperseded(true); return false }
        return true
    }

    /** Fresh prekeys for the restored install (replacing the old phone's on every node), then a reset to every contact. */
    private suspend fun finishRestore(api: NodeApi) {
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
