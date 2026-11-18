package app.ottermsg.core.data.security

import app.ottermsg.core.crypto.PinHash
import app.ottermsg.core.data.Clock
import app.ottermsg.core.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent app-lock state (Settings in the app, a fake in tests). */
interface AppLockStore {
    /** salt ‖ hash of the PIN; null = app lock off. */
    suspend fun pinHash(): ByteArray?
    suspend fun setPinHash(v: ByteArray?)
    suspend fun biometric(): Boolean
    suspend fun setBiometric(v: Boolean)
    suspend fun timeoutMs(): Long
    suspend fun setTimeoutMs(v: Long)
    suspend fun failures(): Int
    suspend fun setFailures(v: Int)
    suspend fun lockedUntilMs(): Long
    suspend fun setLockedUntilMs(v: Long)
}

interface PinHasher {
    fun hash(pin: CharArray): ByteArray
    fun verify(pin: CharArray, stored: ByteArray): Boolean
}

object Argon2PinHasher : PinHasher {
    override fun hash(pin: CharArray) = PinHash.hash(pin)
    override fun verify(pin: CharArray, stored: ByteArray) = PinHash.verify(pin, stored)
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val triesLeft: Int) : PinResult
    data class Wait(val untilMs: Long) : PinResult
}

/**
 * App lock (D121): a UI gate over the already encrypted database. All decisions are here, the screens only render.
 *
 * Off until the user turns it on. Wrong PINs: [FREE_TRIES] free, then [BASE_WAIT_MS] doubling up to [MAX_WAIT_MS].
 * Every try is written to the store *before* the PIN is checked, so killing the app during the check, or restarting
 * it, never resets the count or the wait. Callers run the suspend functions off the main thread (Argon2 ~ 16 MiB).
 */
@Singleton
class AppLock @Inject constructor(private val store: AppLockStore, private val clock: Clock, private val hasher: PinHasher) {
    private val _initialized = MutableStateFlow(false)
    /** [init] has read the store; until then [enabled]/[locked] are not meaningful (show nothing, post no text). */
    val initialized: StateFlow<Boolean> = _initialized
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked
    private val mutex = Mutex()
    @Volatile private var backgroundAt = 0L
    @Volatile private var timeout = DEFAULT_TIMEOUT_MS

    /** At process start (idempotent): locked whenever the lock is on. */
    suspend fun init() = mutex.withLock {
        if (_initialized.value) return@withLock
        val on = store.pinHash() != null
        timeout = store.timeoutMs()
        _enabled.value = on
        if (on) _locked.value = true
        _initialized.value = true
    }

    /** The app left the screen (process lifecycle onStop). With «Сразу» it locks right here. */
    fun onBackground() {
        backgroundAt = clock.now()
        if (_enabled.value && timeout <= 0) _locked.value = true
    }

    /** The app is back on the screen (onStart). Synchronous, so no chat frame shows before the lock. */
    fun onForeground() {
        val since = backgroundAt
        backgroundAt = 0
        if (!_enabled.value || since == 0L) return
        val away = clock.now() - since
        if (away >= timeout || away < 0) _locked.value = true // a clock set back: lock rather than guess
    }

    fun lockNow() { if (_enabled.value) _locked.value = true }

    /** Checks the PIN with the backoff and unlocks on [PinResult.Ok]. */
    suspend fun tryPin(pin: CharArray): PinResult = checkPin(pin).also { if (it == PinResult.Ok) _locked.value = false }

    /**
     * Checks the PIN with the same backoff, without unlocking (to turn the lock off or change the PIN). Off → Ok.
     * During a wait even the right PIN gets [PinResult.Wait].
     */
    suspend fun checkPin(pin: CharArray): PinResult = mutex.withLock {
        val stored = store.pinHash() ?: return@withLock PinResult.Ok
        val now = clock.now()
        val until = store.lockedUntilMs()
        if (until > now) {
            // A wait longer than the cap can only come from a clock that was set back: cap it instead of locking forever.
            if (until - now > MAX_WAIT_MS) return@withLock PinResult.Wait((now + MAX_WAIT_MS).also { store.setLockedUntilMs(it) })
            return@withLock PinResult.Wait(until)
        }
        val n = store.failures() + 1
        // Count the try first: if the app dies during the slow check, the try still counts.
        store.setFailures(n)
        val waitUntil = if (n >= FREE_TRIES) now + waitMs(n) else 0L
        if (waitUntil > 0) store.setLockedUntilMs(waitUntil)
        if (hasher.verify(pin, stored)) {
            store.setFailures(0); store.setLockedUntilMs(0)
            PinResult.Ok
        } else if (waitUntil > 0) PinResult.Wait(waitUntil) else PinResult.Wrong(FREE_TRIES - n)
    }

    /**
     * The wait still running, e.g. after the app was force-stopped during it, so the lock screen shows its countdown at
     * once instead of only after the next try. Null when there is none (or the lock is off).
     */
    suspend fun currentWait(): PinResult.Wait? = mutex.withLock {
        if (store.pinHash() == null) return@withLock null
        val now = clock.now()
        val until = store.lockedUntilMs()
        when {
            until <= now -> null
            until - now > MAX_WAIT_MS -> PinResult.Wait((now + MAX_WAIT_MS).also { store.setLockedUntilMs(it) })
            else -> PinResult.Wait(until)
        }
    }

    /** Only after a successful BiometricPrompt (BIOMETRIC_STRONG). Does not reset the PIN backoff. */
    fun unlockedByDevice() { _locked.value = false }

    /** Turns the lock on with a new PIN (4–8 ASCII digits). */
    suspend fun enable(pin: CharArray, biometric: Boolean) = mutex.withLock {
        require(validPin(pin)) { "PIN must be ${PIN_LENGTH.first}–${PIN_LENGTH.last} digits" }
        store.setPinHash(hasher.hash(pin)); store.setBiometric(biometric)
        store.setFailures(0); store.setLockedUntilMs(0)
        _enabled.value = true
        _locked.value = false
    }

    /** «Сменить PIN»: [old] goes through [checkPin] (same backoff); the new PIN is set only on Ok. */
    suspend fun changePin(old: CharArray, new: CharArray): PinResult {
        require(validPin(new)) { "PIN must be ${PIN_LENGTH.first}–${PIN_LENGTH.last} digits" }
        val r = checkPin(old)
        if (r == PinResult.Ok) mutex.withLock { if (store.pinHash() != null) store.setPinHash(hasher.hash(new)) }
        return r
    }

    /**
     * Turns the lock off and forgets the PIN. The caller has proven the user: the PIN via [checkPin], or «Забыли PIN?»
     * through the phone's own screen lock (device credential).
     */
    suspend fun disable() = mutex.withLock {
        store.setPinHash(null); store.setBiometric(false); store.setFailures(0); store.setLockedUntilMs(0)
        _enabled.value = false
        _locked.value = false
    }

    suspend fun biometric(): Boolean = store.biometric()
    suspend fun setBiometric(v: Boolean) = store.setBiometric(v)
    suspend fun timeoutMs(): Long = store.timeoutMs()
    suspend fun setTimeoutMs(v: Long) {
        require(v in TIMEOUTS_MS) { "timeout must be one of $TIMEOUTS_MS" }
        store.setTimeoutMs(v)
        timeout = v
    }

    companion object {
        const val FREE_TRIES = 5
        const val BASE_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 15 * 60_000L
        const val DEFAULT_TIMEOUT_MS = 60_000L
        /** «Блокировать»: Сразу / Через 1 минуту / Через 5 минут / Через 30 минут. */
        val TIMEOUTS_MS = listOf(0L, 60_000L, 5 * 60_000L, 30 * 60_000L)
        val PIN_LENGTH = 4..8

        fun validPin(pin: CharArray): Boolean = pin.size in PIN_LENGTH && pin.all { it in '0'..'9' }

        /** Wait after the [n]-th wrong try in a row (n ≥ [FREE_TRIES]): 30 s, 60 s, 120 s … capped at 15 min. */
        fun waitMs(n: Int): Long = minOf(BASE_WAIT_MS shl (n - FREE_TRIES).coerceIn(0, 10), MAX_WAIT_MS)
    }
}

/** Settings-backed store. */
class SettingsAppLockStore @Inject constructor(private val s: Settings) : AppLockStore {
    override suspend fun pinHash() = s.appLockHash()
    override suspend fun setPinHash(v: ByteArray?) = s.setAppLockHash(v)
    override suspend fun biometric() = s.appLockBiometric()
    override suspend fun setBiometric(v: Boolean) = s.setAppLockBiometric(v)
    override suspend fun timeoutMs() = s.appLockTimeoutMs()
    override suspend fun setTimeoutMs(v: Long) = s.setAppLockTimeoutMs(v)
    override suspend fun failures() = s.appLockFailures()
    override suspend fun setFailures(v: Int) = s.setAppLockFailures(v)
    override suspend fun lockedUntilMs() = s.appLockUntilMs()
    override suspend fun setLockedUntilMs(v: Long) = s.setAppLockUntilMs(v)
}
