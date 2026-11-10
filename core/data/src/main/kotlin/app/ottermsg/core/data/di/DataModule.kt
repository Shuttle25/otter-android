package app.ottermsg.core.data.di

import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.account.SeedVault
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.data.registration.NodeConnector
import app.ottermsg.core.data.registration.Registrar
import app.ottermsg.core.data.registration.Registration
import app.ottermsg.core.data.registration.TransportConnector
import app.ottermsg.core.data.sync.SyncEngine
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.database.key.KeystoreKeyWrapper
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
    @Provides @Singleton fun connector(transport: Transport): NodeConnector = TransportConnector(transport)
    @Provides @Singleton fun seedVault(): SeedVault = SeedVault(KeystoreKeyWrapper("otter_seed_kek"))
    @Provides @Singleton @ApplicationScope fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides fun onlineApi(engine: SyncEngine): OnlineApi = engine
    @Provides fun registrar(r: Registration): Registrar = r
    @Provides fun conversationStore(r: MessageRepository): ConversationStore = r
}
