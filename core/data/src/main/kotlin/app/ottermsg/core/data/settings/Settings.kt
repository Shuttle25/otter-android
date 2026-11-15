package app.ottermsg.core.data.settings

import app.ottermsg.core.crypto.BackupKeys
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
    private suspend fun long(key: String): Long? = dao.get(key)?.let { ByteBuffer.wrap(it).long }
    private suspend fun putLong(key: String, v: Long) = dao.put(SettingEntity(key, ByteBuffer.allocate(8).putLong(v).array()))

    suspend fun registrationId(): Int = checkNotNull(int(REGISTRATION_ID)) { "registration id missing" }
    suspend fun setRegistrationId(v: Int) = putInt(REGISTRATION_ID, v)
    suspend fun nextOneTimeId(): Int = int(NEXT_ONE_TIME_ID) ?: 1
    suspend fun setNextOneTimeId(v: Int) = putInt(NEXT_ONE_TIME_ID, v)
    suspend fun networkPub(): ByteArray = checkNotNull(dao.get(NETWORK_PUB)) { "network key missing" }
    suspend fun setNetworkPub(v: ByteArray) = dao.put(SettingEntity(NETWORK_PUB, v))
    /** Our invite chain (D100): the INVITE record and the redeem proof, copied into every new version of our entry. */
    suspend fun ownInvite(): Pair<ByteArray, ByteArray>? {
        val rec = dao.get(OWN_INVITE_RECORD) ?: return null
        val proof = dao.get(OWN_INVITE_PROOF) ?: return null
        return rec to proof
    }
    suspend fun setOwnInvite(record: ByteArray, proof: ByteArray) {
        dao.put(SettingEntity(OWN_INVITE_RECORD, record)); dao.put(SettingEntity(OWN_INVITE_PROOF, proof))
    }
    /** M1 flag: a refill for the connected node was pending. Read once for compatibility. */
    suspend fun prekeysLow(): Boolean = (int(PREKEYS_LOW) ?: 0) != 0
    suspend fun setPrekeysLow(v: Boolean) = putInt(PREKEYS_LOW, if (v) 1 else 0)

    /** Owner node ids (hex) whose one-time prekey refill has not been uploaded yet. */
    suspend fun pendingRefills(): Set<String> =
        dao.get(PENDING_REFILLS)?.decodeToString()?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    suspend fun setPendingRefills(owners: Set<String>) = dao.put(SettingEntity(PENDING_REFILLS, owners.joinToString("\n").encodeToByteArray()))

    /** Recent prekey refill times per owner node (hex), for the refill bounds (D108). */
    suspend fun refillTimes(): Map<String, List<Long>> =
        dao.get(REFILL_TIMES)?.decodeToString()?.split('\n')?.mapNotNull { line ->
            val k = line.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            k to line.substringAfter(':').split(',').mapNotNull { it.toLongOrNull() }
        }?.toMap() ?: emptyMap()
    suspend fun setRefillTimes(m: Map<String, List<Long>>) =
        dao.put(SettingEntity(REFILL_TIMES, m.entries.joinToString("\n") { (k, v) -> k + ":" + v.joinToString(",") }.encodeToByteArray()))

    /** D104: `<user hex>:<group hex>` pairs whose revocation GroupRevocations already handled (rotate once, remove once). */
    suspend fun revocationsHandled(): Set<String> =
        dao.get(REVOCATIONS_HANDLED)?.decodeToString()?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    suspend fun addRevocationsHandled(keys: Collection<String>) {
        if (keys.isEmpty()) return
        dao.put(SettingEntity(REVOCATIONS_HANDLED, (revocationsHandled() + keys).joinToString("\n").encodeToByteArray()))
    }

    /** Accounts (hex) seen losing a doubly redeemed invite (D102). Grows only: a later batch hiding the winner changes nothing. */
    suspend fun duplicateLosers(): Set<String> =
        dao.get(DUPLICATE_LOSERS)?.decodeToString()?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    suspend fun setDuplicateLosers(users: Set<String>) = dao.put(SettingEntity(DUPLICATE_LOSERS, users.joinToString("\n").encodeToByteArray()))

    /** Verified inviters (hex) per contact (hex), from their entries' invite chains; kept when a node hides an entry. */
    suspend fun contactInviters(): Map<String, Set<String>> =
        dao.get(CONTACT_INVITERS)?.decodeToString()?.split('\n')?.mapNotNull { line ->
            val k = line.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            k to line.substringAfter(':').split(',').filter { it.isNotEmpty() }.toSet()
        }?.toMap() ?: emptyMap()
    suspend fun setContactInviters(m: Map<String, Set<String>>) =
        dao.put(SettingEntity(CONTACT_INVITERS, m.entries.joinToString("\n") { (k, v) -> k + ":" + v.joinToString(",") }.encodeToByteArray()))

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
    fun observeLong(key: String, default: Long): Flow<Long> = dao.observe(key).map { it?.let { b -> ByteBuffer.wrap(b).long } ?: default }

    // ---- install identity (D52) ----
    /** Time this install was created (registration or restore); 0 for installs from before M6. */
    suspend fun installedMs(): Long = long(INSTALLED_MS) ?: 0
    suspend fun setInstalledMs(v: Long) = putLong(INSTALLED_MS, v)
    /** A restore wrote the database but prekeys/resets are not done yet (InstallGuard finishes it). */
    suspend fun restorePending(): Boolean = (int(RESTORE_PENDING) ?: 0) != 0
    suspend fun setRestorePending(v: Boolean) = putInt(RESTORE_PENDING, if (v) 1 else 0)
    /** Another phone restored this account; this install stopped syncing. */
    suspend fun superseded(): Boolean = (int(SUPERSEDED) ?: 0) != 0
    suspend fun setSuperseded(v: Boolean) = putInt(SUPERSEDED, if (v) 1 else 0)
    fun observeSuperseded(): Flow<Boolean> = observeInt(SUPERSEDED, 0).map { it != 0 }
    /** This install's INSTALL record reached a node (C14); cleared by «Этот телефон снова главный». */
    suspend fun installPublished(): Boolean = (int(INSTALL_PUBLISHED) ?: 0) != 0
    suspend fun setInstallPublished(v: Boolean) = putInt(INSTALL_PUBLISHED, if (v) 1 else 0)

    // ---- password backup (D55) ----
    /** backup_key ‖ auth_secret wrapped by SeedVault; null = backup off. */
    suspend fun backupSecret(): ByteArray? = dao.get(BACKUP_SECRET)
    suspend fun setBackupSecret(v: ByteArray) = dao.put(SettingEntity(BACKUP_SECRET, v))
    suspend fun backupSalt(): ByteArray? = dao.get(BACKUP_SALT)
    suspend fun setBackupSalt(v: ByteArray) = dao.put(SettingEntity(BACKUP_SALT, v))
    suspend fun backupVersion(): Long = long(BACKUP_VERSION) ?: 0
    suspend fun setBackupVersion(v: Long) = putLong(BACKUP_VERSION, v)
    suspend fun backupLastMs(): Long = long(BACKUP_LAST_MS) ?: 0
    suspend fun setBackupLastMs(v: Long) = putLong(BACKUP_LAST_MS, v)
    fun observeBackupLastMs(): Flow<Long> = observeLong(BACKUP_LAST_MS, 0)
    /** Last backup error code (BackupManager.ERR_*), 0 = none. */
    suspend fun backupError(): Int = int(BACKUP_ERROR) ?: 0
    suspend fun setBackupError(v: Int) = putInt(BACKUP_ERROR, v)
    fun observeBackupError(): Flow<Int> = observeInt(BACKUP_ERROR, 0)
    fun observeBackupEnabled(): Flow<Boolean> = dao.observe(BACKUP_SECRET).map { it != null }
    /** Profile of the stored backup keys (C1); P1 for backups made before 0.9.0. */
    suspend fun backupProfile(): BackupKeys.Profile =
        dao.get(BACKUP_PROFILE)?.decodeToString()?.let { n -> BackupKeys.Profile.entries.firstOrNull { it.name == n } }
            ?: BackupKeys.Profile.P1
    suspend fun setBackupProfile(p: BackupKeys.Profile) = dao.put(SettingEntity(BACKUP_PROFILE, p.name.encodeToByteArray()))
    /** C20: the decoy BACKUP_META of an account without a backup has been published. */
    suspend fun backupDecoyDone(): Boolean = (int(BACKUP_DECOY) ?: 0) != 0
    suspend fun setBackupDecoyDone(v: Boolean) = putInt(BACKUP_DECOY, if (v) 1 else 0)

    // ---- push (D60/D61) and notifications (D62) ----
    suspend fun pushToken(): String? = dao.get(PUSH_TOKEN)?.decodeToString()
    suspend fun setPushToken(v: String) = dao.put(SettingEntity(PUSH_TOKEN, v.encodeToByteArray()))
    fun observePushToken(): Flow<String?> = dao.observe(PUSH_TOKEN).map { it?.decodeToString() }
    suspend fun pushTokenSent(): String? = dao.get(PUSH_TOKEN_SENT)?.decodeToString()
    suspend fun setPushTokenSent(v: String) = dao.put(SettingEntity(PUSH_TOKEN_SENT, v.encodeToByteArray()))
    /** «Пробуждение через Google» (D94): off = FCM token deleted and withdrawn, battery saver. Default on. */
    suspend fun googleWake(): Boolean = (int(GOOGLE_WAKE) ?: 1) != 0
    suspend fun setGoogleWake(v: Boolean) = putInt(GOOGLE_WAKE, if (v) 1 else 0)
    fun observeGoogleWake(): Flow<Boolean> = observeInt(GOOGLE_WAKE, 1).map { it != 0 }
    /** Google Play services + Firebase configured in this build (set by the app at start). */
    suspend fun pushAvailable(): Boolean = (int(PUSH_AVAILABLE) ?: 0) != 0
    suspend fun setPushAvailable(v: Boolean) = putInt(PUSH_AVAILABLE, if (v) 1 else 0)
    fun observePushAvailable(): Flow<Boolean> = observeInt(PUSH_AVAILABLE, 0).map { it != 0 }
    suspend fun pushLastWakeMs(): Long = long(PUSH_LAST_WAKE_MS) ?: 0
    suspend fun setPushLastWakeMs(v: Long) = putLong(PUSH_LAST_WAKE_MS, v)
    /** Times of recent missed FCM wake-ups (D61). */
    suspend fun pushMisses(): List<Long> = parseMisses(dao.get(PUSH_MISSES))
    suspend fun setPushMisses(v: List<Long>) = dao.put(SettingEntity(PUSH_MISSES, v.joinToString(",").encodeToByteArray()))
    fun observePushMisses(): Flow<List<Long>> = dao.observe(PUSH_MISSES).map(::parseMisses)
    private fun parseMisses(b: ByteArray?): List<Long> = b?.decodeToString()?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
    /** When the engine last went fully offline (no lease); 0 = never. */
    suspend fun lastOfflineMs(): Long = long(LAST_OFFLINE_MS) ?: 0
    suspend fun setLastOfflineMs(v: Long) = putLong(LAST_OFFLINE_MS, v)
    suspend fun notificationsEnabled(): Boolean = (int(NOTIF_ENABLED) ?: 1) != 0
    suspend fun setNotificationsEnabled(v: Boolean) = putInt(NOTIF_ENABLED, if (v) 1 else 0)
    fun observeNotificationsEnabled(): Flow<Boolean> = observeInt(NOTIF_ENABLED, 1).map { it != 0 }
    /** «Звонки только через сервер» (D77): ICE transport policy RELAY; default on. */
    suspend fun callsRelayOnly(): Boolean = (int(CALLS_RELAY_ONLY) ?: 1) != 0
    suspend fun setCallsRelayOnly(v: Boolean) = putInt(CALLS_RELAY_ONLY, if (v) 1 else 0)
    fun observeCallsRelayOnly(): Flow<Boolean> = observeInt(CALLS_RELAY_ONLY, 1).map { it != 0 }
    suspend fun notificationPermissionAsked(): Boolean = (int(NOTIF_ASKED) ?: 0) != 0
    suspend fun setNotificationPermissionAsked() = putInt(NOTIF_ASKED, 1)
    /** The one-time «Звонки на заблокированном экране» prompt (USE_FULL_SCREEN_INTENT, Android 14+) was shown. */
    suspend fun fullScreenIntentAsked(): Boolean = (int(FSI_ASKED) ?: 0) != 0
    suspend fun setFullScreenIntentAsked() = putInt(FSI_ASKED, 1)

    companion object {
        const val PUSH_TOKEN = "push_token"
        const val PUSH_TOKEN_SENT = "push_token_sent"
        const val PUSH_AVAILABLE = "push_available"
        const val GOOGLE_WAKE = "google_wake"
        const val PUSH_LAST_WAKE_MS = "push_last_wake_ms"
        const val PUSH_MISSES = "push_misses"
        const val LAST_OFFLINE_MS = "last_offline_ms"
        const val NOTIF_ENABLED = "notifications_enabled"
        const val NOTIF_ASKED = "notifications_permission_asked"
        const val FSI_ASKED = "full_screen_intent_asked"
        const val CALLS_RELAY_ONLY = "calls_relay_only"
        const val INSTALLED_MS = "installed_ms"
        const val RESTORE_PENDING = "restore_pending"
        const val SUPERSEDED = "superseded"
        const val INSTALL_PUBLISHED = "install_published"
        const val BACKUP_SECRET = "backup_secret"
        const val BACKUP_SALT = "backup_salt"
        const val BACKUP_VERSION = "backup_version"
        const val BACKUP_LAST_MS = "backup_last_ms"
        const val BACKUP_ERROR = "backup_error"
        const val BACKUP_PROFILE = "backup_profile"
        const val BACKUP_DECOY = "backup_decoy_done"
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
        const val OWN_INVITE_RECORD = "own_invite_record"
        const val OWN_INVITE_PROOF = "own_invite_proof"
        const val PREKEYS_LOW = "prekeys_low"
        const val PENDING_REFILLS = "prekeys_pending_owners"
        const val REFILL_TIMES = "prekeys_refill_times"
        const val REVOCATIONS_HANDLED = "revocations_handled"
        const val DUPLICATE_LOSERS = "duplicate_losers"
        const val CONTACT_INVITERS = "contact_inviters"
    }
}
