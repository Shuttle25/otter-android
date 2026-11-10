package app.ottermsg.core.data.account

import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.dao.AccountDao
import app.ottermsg.core.signal.IdentityKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao, private val settings: Settings, private val vault: SeedVault,
) {
    private val _account = MutableStateFlow<LocalAccount?>(null)
    val account: StateFlow<LocalAccount?> = _account
    private val loadMutex = Mutex()

    /** Loads the stored account (if any). Safe to call repeatedly. */
    suspend fun load(): LocalAccount? = loadMutex.withLock {
        _account.value ?: accountDao.get()?.let { e ->
            val seed = vault.unwrap(e.seedEnc)
            LocalAccount(e.userId, e.username, e.displayName, seed, SeedKeys.accountKeyPair(seed),
                IdentityKeys.fromSeed(seed), settings.registrationId()).also { _account.value = it }
        }
    }

    fun require(): LocalAccount = checkNotNull(_account.value) { "no account loaded" }
}
