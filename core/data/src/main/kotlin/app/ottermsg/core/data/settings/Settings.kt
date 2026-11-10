package app.ottermsg.core.data.settings

import app.ottermsg.core.database.dao.SettingsDao
import app.ottermsg.core.database.entity.SettingEntity
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
    suspend fun prekeysLow(): Boolean = (int(PREKEYS_LOW) ?: 0) != 0
    suspend fun setPrekeysLow(v: Boolean) = putInt(PREKEYS_LOW, if (v) 1 else 0)

    companion object {
        const val REGISTRATION_ID = "registration_id"
        const val NEXT_ONE_TIME_ID = "next_one_time_id"
        const val NETWORK_PUB = "network_pub"
        const val PREKEYS_LOW = "prekeys_low"
    }
}
