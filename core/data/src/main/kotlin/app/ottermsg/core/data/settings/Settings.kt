package app.ottermsg.core.data.settings

import app.ottermsg.core.database.dao.SettingsDao
import app.ottermsg.core.database.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** Typed wrapper over the settings table. */
@Singleton
class Settings @Inject constructor(private val dao: SettingsDao) {
    private suspend fun int(key: String): Int? = dao.get(key)?.let { ByteBuffer.wrap(it).int }
    private suspend fun putInt(key: String, v: Int) = dao.put(SettingEntity(key, ByteBuffer.allocate(4).putInt(v).array()))

    suspend fun registrationId(): Int = checkNotNull(int(REGISTRATION_ID)) { "registration id missing" }
    suspend fun setRegistrationId(v: Int) = putInt(REGISTRATION_ID, v)
    suspend fun nextOneTimeId(): Int = int(NEXT_ONE_TIME_ID) ?: 1
    suspend fun setNextOneTimeId(v: Int) = putInt(NEXT_ONE_TIME_ID, v)
    suspend fun networkPub(): ByteArray = checkNotNull(dao.get(NETWORK_PUB)) { "network key missing" }
    suspend fun setNetworkPub(v: ByteArray) = dao.put(SettingEntity(NETWORK_PUB, v))
    /** M1 flag: a refill for the connected node was pending. Read once for compatibility. */
    suspend fun prekeysLow(): Boolean = (int(PREKEYS_LOW) ?: 0) != 0
    suspend fun setPrekeysLow(v: Boolean) = putInt(PREKEYS_LOW, if (v) 1 else 0)

    /** Owner node ids (hex) whose one-time prekey refill has not been uploaded yet. */
    suspend fun pendingRefills(): Set<String> =
        dao.get(PENDING_REFILLS)?.decodeToString()?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    suspend fun setPendingRefills(owners: Set<String>) = dao.put(SettingEntity(PENDING_REFILLS, owners.joinToString("\n").encodeToByteArray()))

    /** Version of our own DIRECTORY_ENTRY as last published or seen (M1–M3 registrations are version 1). */
    suspend fun entryVersion(): Int = int(ENTRY_VERSION) ?: 1
    suspend fun setEntryVersion(v: Int) = putInt(ENTRY_VERSION, v)
    /** A display-name change is saved locally but not yet accepted by a node. */
    suspend fun profilePending(): Boolean = (int(PROFILE_PENDING) ?: 0) != 0
    suspend fun setProfilePending(v: Boolean) = putInt(PROFILE_PENDING, if (v) 1 else 0)

    /** Photo auto-download (D47): [AUTO_WIFI] (default), [AUTO_ANY], [AUTO_MANUAL]. */
    suspend fun photoAutoDownload(): Int = int(PHOTO_AUTO) ?: AUTO_WIFI
    suspend fun setPhotoAutoDownload(v: Int) = putInt(PHOTO_AUTO, v)
    /** Save every downloaded photo to the gallery (default off). */
    suspend fun photoSaveToGallery(): Boolean = (int(PHOTO_GALLERY) ?: 0) != 0
    suspend fun setPhotoSaveToGallery(v: Boolean) = putInt(PHOTO_GALLERY, if (v) 1 else 0)
    /** Raw value changes of one key (settings screens). */
    fun observe(key: String): Flow<ByteArray?> = dao.observe(key)
    fun observeInt(key: String, default: Int): Flow<Int> = dao.observe(key).map { it?.let { b -> ByteBuffer.wrap(b).int } ?: default }

    companion object {
        const val PHOTO_AUTO = "photo_auto_download"
        const val PHOTO_GALLERY = "photo_save_gallery"
        const val AUTO_WIFI = 0
        const val AUTO_ANY = 1
        const val AUTO_MANUAL = 2
        const val ENTRY_VERSION = "profile_entry_version"
        const val PROFILE_PENDING = "profile_pending"
        const val REGISTRATION_ID = "registration_id"
        const val NEXT_ONE_TIME_ID = "next_one_time_id"
        const val NETWORK_PUB = "network_pub"
        const val PREKEYS_LOW = "prekeys_low"
        const val PENDING_REFILLS = "prekeys_pending_owners"
    }
}
