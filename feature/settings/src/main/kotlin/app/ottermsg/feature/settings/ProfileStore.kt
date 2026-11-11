package app.ottermsg.feature.settings

import app.ottermsg.core.data.account.AccountRepository
import app.ottermsg.core.data.account.ProfileError
import app.ottermsg.core.data.di.ApplicationScope
import app.ottermsg.core.data.profile.ProfileRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the profile screens show about the account. */
data class ProfileInfo(val userId: ByteArray, val username: String, val displayName: String)

/** The profile screens' view of the account (a fake in ViewModel tests, so they need no Room). */
interface ProfileStore {
    val profile: StateFlow<ProfileInfo?>
    val pending: StateFlow<Boolean>
    /** Fails with [ProfileError] for an empty or too long name. */
    suspend fun setDisplayName(name: String): Result<Unit>
}

@Singleton
class RealProfileStore @Inject constructor(
    accounts: AccountRepository, private val repo: ProfileRepository, @ApplicationScope scope: CoroutineScope,
) : ProfileStore {
    override val profile: StateFlow<ProfileInfo?> = accounts.account
        .map { a -> a?.let { ProfileInfo(it.userId, it.username, it.displayName) } }
        .stateIn(scope, SharingStarted.Eagerly, accounts.account.value?.let { ProfileInfo(it.userId, it.username, it.displayName) })
    override val pending: StateFlow<Boolean> = repo.pending

    init { scope.launch { repo.refresh() } }

    override suspend fun setDisplayName(name: String) = repo.setDisplayName(name)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileStoreModule {
    @Binds abstract fun bind(store: RealProfileStore): ProfileStore
}
