package com.beamdrop.app

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.beamdrop.app.i18n.tr

/** Fingerprint / face / screen-lock check used when Sendit opens (Settings → App lock). */
object AppLock {

    private val allowed: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

    /** True when the phone has a fingerprint, face or screen lock set up. */
    fun canUse(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS) return true
        val km = context.getSystemService(android.app.KeyguardManager::class.java)
        return km?.isDeviceSecure == true
    }

    fun prompt(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(tr("Unlock Sendit"))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(allowed)
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()
        runCatching { prompt.authenticate(info) }
    }
}
