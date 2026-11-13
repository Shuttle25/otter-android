package app.ottermsg.core.data.di

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.SeedVault
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.data.group.GroupRepository
import app.ottermsg.core.data.group.GroupStore
import app.ottermsg.core.data.media.AndroidNetworkInfo
import app.ottermsg.core.data.media.MediaRepository
import app.ottermsg.core.data.media.NetworkInfo
import app.ottermsg.core.data.media.PhotoStore
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.data.registration.Registrar
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.registration.TransportConnector
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.key.KeystoreKeyWrapper
import app.ottermsg.core.data.network.RoomEndpointStats
import app.ottermsg.core.data.network.RoomNetLog
import app.ottermsg.core.network.EndpointStats
import app.ottermsg.core.network.NetLog
import app.ottermsg.core.network.NodeSelector
import app.ottermsg.core.network.OkHttpTransport
import app.ottermsg.core.network.Transport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides fun clock(): Clock = Clock { System.currentTimeMillis() }
    @Provides @Singleton fun transport(): Transport = OkHttpTransport()
    @Provides @Singleton fun endpointStats(s: RoomEndpointStats): EndpointStats = s
    @Provides @Singleton fun netLog(l: RoomNetLog): NetLog = l
    @Provides @Singleton fun nodeSelector(transport: Transport, stats: EndpointStats, clock: Clock, netLog: NetLog): NodeSelector =
        NodeSelector(transport, stats, clock::now, netLog = netLog)
    @Provides @Singleton fun connector(selector: NodeSelector): NodeConnector = TransportConnector(selector)
    @Provides @Singleton fun seedVault(): SeedVault = SeedVault(KeystoreKeyWrapper("otter_seed_kek"))
    @Provides @Singleton @ApplicationScope fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides fun onlineApi(engine: SyncEngine): OnlineApi = engine
    @Provides fun registrar(r: Registration): Registrar = r
    @Provides fun restorer(r: app.ottermsg.core.data.backup.RestoreManager): app.ottermsg.core.data.backup.Restorer = r
    @Provides fun conversationStore(r: MessageRepository): ConversationStore = r
    @Provides fun groupStore(r: GroupRepository): GroupStore = r
    @Provides fun networkInfo(n: AndroidNetworkInfo): NetworkInfo = n
    @Provides fun photoStore(r: MediaRepository): PhotoStore = r
    @Provides @CacheDir fun cacheDir(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): java.io.File =
        context.cacheDir
    @Provides fun updateDownloader(u: app.ottermsg.core.data.news.ApkUpdater): app.ottermsg.core.data.news.UpdateDownloader = u
    @Provides fun callInbox(m: app.ottermsg.core.data.calls.CallManager): app.ottermsg.core.data.calls.CallInbox = m
    @Provides fun callTransport(s: app.ottermsg.core.data.calls.CallSignaling): app.ottermsg.core.data.calls.CallTransport = s
    @Provides fun callLink(l: app.ottermsg.core.data.calls.SyncCallLink): app.ottermsg.core.data.calls.CallLink = l
    @Provides fun newsKeys(m: app.ottermsg.core.data.sync.ManifestUpdater): app.ottermsg.core.data.sync.NewsKeys = m
}
