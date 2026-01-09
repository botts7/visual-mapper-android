package com.visualmapper.companion.security

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/**
 * App Lock Manager
 *
 * Provides PIN and biometric authentication to protect the companion app.
 * This prevents unauthorized access to:
 * - MQTT credentials
 * - Device passcodes
 * - App whitelist configuration
 * - Sensor data
 *
 * Security features:
 * - PIN stored as SHA-256 hash (not plain text)
 * - Biometric authentication via Android BiometricPrompt
 * - Lockout after failed attempts
 * - Auto-lock on app background
 */
class AppLockManager(
    private val context: Context,
    private val securePrefs: SecurePreferences
) {

    companion object {
        private const val TAG = "AppLockManager"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds
    }

    private var failedAttempts = 0
    private var lockoutUntil: Long = 0

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked

    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked

    // =========================================================================
    // Lock State Management
    // =========================================================================

    /**
     * Check if app lock is configured
     */
    fun isLockConfigured(): Boolean = securePrefs.hasAppLock()

    /**
     * Lock the app (call on pause/background)
     */
    fun lock() {
        _isUnlocked.value = false
        _isLocked.value = true
        Log.d(TAG, "App locked")
    }

    /**
     * Check if currently in lockout period
     */
    fun isInLockout(): Boolean {
        if (lockoutUntil > System.currentTimeMillis()) {
            return true
        }
        // Reset if lockout expired
        if (lockoutUntil > 0) {
            lockoutUntil = 0
            failedAttempts = 0
        }
        return false
    }

    /**
     * Get remaining lockout time in seconds
     */
    fun getRemainingLockoutSeconds(): Int {
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) (remaining / 1000).toInt() else 0
    }

    // =========================================================================
    // PIN Authentication
    // =========================================================================

    /**
     * Set up PIN lock
     */
    fun setupPin(pin: String): Boolean {
        if (pin.length < 4) {
            Log.w(TAG, "PIN too short (minimum 4 digits)")
            return false
        }

        val pinHash = hashPin(pin)
        securePrefs.appPinHash = pinHash
        Log.i(TAG, "PIN lock configured")
        return true
    }

    /**
     * Verify PIN and unlock if correct
     */
    fun verifyPin(pin: String): Boolean {
        if (isInLockout()) {
            Log.w(TAG, "In lockout period")
            return false
        }

        val storedHash = securePrefs.appPinHash
        if (storedHash == null) {
            Log.w(TAG, "No PIN configured")
            return false
        }

        val inputHash = hashPin(pin)
        if (inputHash == storedHash) {
            // Success
            failedAttempts = 0
            _isUnlocked.value = true
            _isLocked.value = false
            Log.i(TAG, "PIN verified, app unlocked")
            return true
        } else {
            // Failed attempt
            failedAttempts++
            Log.w(TAG, "Invalid PIN, attempt $failedAttempts of $MAX_FAILED_ATTEMPTS")

            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                Log.w(TAG, "Max attempts reached, lockout for ${LOCKOUT_DURATION_MS / 1000}s")
            }
            return false
        }
    }

    /**
     * Remove PIN lock
     */
    fun removePin() {
        securePrefs.appPinHash = null
        Log.i(TAG, "PIN lock removed")
    }

    /**
     * Change PIN (requires current PIN verification)
     */
    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verifyPin(currentPin)) {
            return false
        }
        return setupPin(newPin)
    }

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // Biometric Authentication
    // =========================================================================

    /**
     * Check if biometric authentication is available
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Log.d(TAG, "No biometric hardware")
                false
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Log.d(TAG, "Biometric hardware unavailable")
                false
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Log.d(TAG, "No biometrics enrolled")
                false
            }
            else -> false
        }
    }

    /**
     * Enable biometric authentication
     */
    fun enableBiometric(): Boolean {
        if (!isBiometricAvailable()) {
            Log.w(TAG, "Biometric not available")
            return false
        }
        securePrefs.biometricEnabled = true
        Log.i(TAG, "Biometric authentication enabled")
        return true
    }

    /**
     * Disable biometric authentication
     */
    fun disableBiometric() {
        securePrefs.biometricEnabled = false
        Log.i(TAG, "Biometric authentication disabled")
    }

    /**
     * Check if biometric is enabled
     */
    fun isBiometricEnabled(): Boolean = securePrefs.biometricEnabled

    /**
     * Authenticate with biometric
     *
     * @param activity The activity to show the biometric prompt
     * @param onSuccess Called when authentication succeeds
     * @param onError Called when authentication fails
     */
    fun authenticateWithBiometric(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isBiometricEnabled()) {
            onError("Biometric not enabled")
            return
        }

        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                failedAttempts = 0
                _isUnlocked.value = true
                _isLocked.value = false
                Log.i(TAG, "Biometric authentication succeeded")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.e(TAG, "Biometric error: $errString")
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                failedAttempts++
                Log.w(TAG, "Biometric authentication failed, attempt $failedAttempts")

                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                    onError("Too many failed attempts. Please wait.")
                }
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Visual Mapper Companion")
            .setSubtitle("Authenticate to access the app")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // =========================================================================
    // Unlock Methods
    // =========================================================================

    /**
     * Unlock without authentication (for when no lock is configured)
     */
    fun unlockWithoutAuth() {
        if (!isLockConfigured()) {
            _isUnlocked.value = true
            _isLocked.value = false
            Log.d(TAG, "Unlocked (no lock configured)")
        }
    }
}
