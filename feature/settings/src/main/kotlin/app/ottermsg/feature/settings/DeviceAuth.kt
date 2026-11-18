package app.ottermsg.feature.settings

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * BiometricPrompt wrappers (D121): fingerprint/face for the app lock, and the phone's own screen lock for «Забыли PIN?»
 * (and for showing the recovery phrase, D124). Call from a resumed [FragmentActivity]; a cancelled prompt does nothing.
 */
object DeviceAuth {
    /** A class 3 (strong) fingerprint/face is enrolled: only then is «Разблокировка отпечатком» offered. */
    fun biometricAvailable(c: Context): Boolean =
        BiometricManager.from(c).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** The phone has a screen lock (PIN, pattern or password) that [deviceCredential] can ask for. */
    fun deviceSecure(c: Context): Boolean = c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Fingerprint/face; the [negative] button (e.g. «PIN») just closes the prompt. */
    fun biometric(a: FragmentActivity, title: String, negative: String, onOk: () -> Unit) =
        prompt(a, onOk, {}, BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG).build())

    /**
     * The phone's own screen lock (its PIN/pattern/password, or its biometric). [onUnavailable] when the phone has no
     * screen lock. BIOMETRIC_STRONG|DEVICE_CREDENTIAL is unsupported on API 28–29, hence WEAK there.
     */
    fun deviceCredential(a: FragmentActivity, title: String, onOk: () -> Unit, onUnavailable: () -> Unit) {
        if (!deviceSecure(a)) return onUnavailable()
        val auth = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        prompt(a, onOk, onUnavailable, BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth).build())
    }

    private fun prompt(a: FragmentActivity, onOk: () -> Unit, onUnavailable: () -> Unit, info: BiometricPrompt.PromptInfo) {
        BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) onUnavailable()
            }
        }).authenticate(info)
    }
}
