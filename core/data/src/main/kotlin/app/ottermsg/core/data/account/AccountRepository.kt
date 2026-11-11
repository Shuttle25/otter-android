package app.ottermsg.core.data.account

import app.ottermsg.core.crypto.SeedKeys
import app.ottermsg.core.data.settings.Settings
import app.ottermsg.core.database.dao.AccountDao
import app.ottermsg.core.database.entity.AccountEntity
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
        _account.value ?: accountDao.get()?.let { e -> build(e).also { _account.value = it } }
    }

    /** Re-reads the account row (after a profile change). */
    suspend fun reload(): LocalAccount? = loadMutex.withLock {
        accountDao.get()?.let { e ->
            val cur = _account.value
            val acc = if (cur != null && cur.userId.contentEquals(e.userId)) cur.copyWith(e.displayName) else build(e)
            acc.also { _account.value = it }
        }
    }

    private suspend fun build(e: AccountEntity): LocalAccount {
        val seed = vault.unwrap(e.seedEnc)
        return LocalAccount(e.userId, e.username, e.displayName, seed, SeedKeys.accountKeyPair(seed),
            IdentityKeys.fromSeed(seed), settings.registrationId(), e.createdMs)
    }

    fun require(): LocalAccount = checkNotNull(_account.value) { "no account loaded" }
}
