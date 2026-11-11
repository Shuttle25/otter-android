package app.ottermsg.feature.settings

import app.ottermsg.core.data.account.ProfileError
import kotlinx.coroutines.flow.MutableStateFlow

class FakeProfileStore(name: String) : ProfileStore {
    override val profile = MutableStateFlow<ProfileInfo?>(ProfileInfo(ByteArray(16) { 1 }, "mama", name))
    override val pending = MutableStateFlow(false)
    var saved: String? = null
    override suspend fun setDisplayName(name: String): Result<Unit> {
        val n = name.trim()
        if (n.isEmpty()) return Result.failure(ProfileError.Empty)
        saved = n
        profile.value = profile.value!!.copy(displayName = n)
        pending.value = true
        return Result.success(Unit)
    }
}
