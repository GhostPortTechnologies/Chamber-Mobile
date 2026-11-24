package com.ghostporttechnologies.chamber

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Why a confirmation didn't happen, in words for the person. Null = confirmed. */
typealias ConfirmResult = String?

/**
 * "It's you": fingerprint, face or the phone's own PIN, before an approval or an Allow. This is a check on the phone;
 * Chamber's own rules (who may approve, re-check, screen lock) still apply on the server.
 */
fun FragmentActivity.confirmItsYou(title: String, subtitle: String, done: (ConfirmResult) -> Unit) {
    val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        done("To approve on this phone, set a screen lock (PIN, pattern, fingerprint or face) in Android settings first.")
        return
    }
    val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(null)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done("Not confirmed, so nothing was sent.")
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
            .setConfirmationRequired(true)
            .build(),
    )
}
