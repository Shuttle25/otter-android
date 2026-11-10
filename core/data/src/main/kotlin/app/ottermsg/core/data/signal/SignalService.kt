package app.ottermsg.core.data.signal

import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.database.dao.ContactDao
import app.ottermsg.core.database.dao.SignalKvDao
import app.ottermsg.core.signal.OtterProtocolStore
import app.ottermsg.core.signal.SignalSessions
import app.ottermsg.core.signal.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** All libsignal use goes through [withSessions] on Dispatchers.IO under one Mutex. */
@Singleton
class SignalService @Inject constructor(
    private val accounts: AccountRepository, private val kvDao: SignalKvDao, private val contacts: ContactDao,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var cached: Pair<OtterProtocolStore, SignalSessions>? = null

    suspend fun <T> withSessions(block: (OtterProtocolStore, SignalSessions) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val (store, sessions) = cached ?: run {
                val a = accounts.require()
                val store = OtterProtocolStore(a.identity, a.registrationId, RoomSignalKeyValue(kvDao)) { addr ->
                    appScope.launch { contacts.markIdentityChanged(addr.name.hexToBytes()) }
                }
                (store to SignalSessions(store, a.userId)).also { cached = it }
            }
            block(store, sessions)
        }
    }
}
