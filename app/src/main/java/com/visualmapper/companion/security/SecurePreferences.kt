package com.visualmapper.companion.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.visualmapper.companion.navigation.NavigationLearningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Secure Preferences Manager
 *
 * Uses Android's EncryptedSharedPreferences backed by AndroidKeyStore.
 * All sensitive data (MQTT credentials, passcodes, etc.) is encrypted at rest.
 *
 * Security features:
 * - AES-256-GCM encryption
 * - Keys stored in Android KeyStore (hardware-backed on supported devices)
 * - Automatic key rotation not implemented (would require migration strategy)
 */
class SecurePreferences(context: Context) {

    companion object {
        private const val TAG = "SecurePreferences"
        private const val PREFS_NAME = "visual_mapper_secure_prefs"

        // Keys for stored values
        const val KEY_MQTT_BROKER = "mqtt_broker"
        const val KEY_MQTT_PORT = "mqtt_port"
        const val KEY_MQTT_USERNAME = "mqtt_username"
        const val KEY_MQTT_PASSWORD = "mqtt_password"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_APP_PIN_HASH = "app_pin_hash"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_WHITELISTED_APPS = "whitelisted_apps"
        const val KEY_PRIVACY_CONSENT_GIVEN = "privacy_consent_given"
        const val KEY_PRIVACY_CONSENT_DATE = "privacy_consent_date"
        const val KEY_DATA_RETENTION_DAYS = "data_retention_days"
        const val KEY_AUDIT_LOGGING_ENABLED = "audit_logging_enabled"
        const val KEY_APP_CONSENTS = "app_consents_json"
        const val KEY_SENSITIVE_DETECTION_ENABLED = "sensitive_detection_enabled"
        const val KEY_NAVIGATION_LEARNING_ENABLED = "navigation_learning_enabled"
        const val KEY_NAVIGATION_LEARNING_MODE = "navigation_learning_mode"
    }

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback or reset if encryption keys are corrupted (common on emulators or reinstall)
        Log.e(TAG, "Error creating EncryptedSharedPreferences, resetting...", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // =========================================================================
    // MQTT Credentials
    // =========================================================================

    var mqttBroker: String?
        get() = encryptedPrefs.getString(KEY_MQTT_BROKER, null)
        set(value) = encryptedPrefs.edit().putString(KEY_MQTT_BROKER, value).apply()

    var mqttPort: Int
        get() = encryptedPrefs.getInt(KEY_MQTT_PORT, 1883)
        set(value) = encryptedPrefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    var mqttUsername: String?
        get() = encryptedPrefs.getString(KEY_MQTT_USERNAME, null)
        set(value) = encryptedPrefs.edit().putString(KEY_MQTT_USERNAME, value).apply()

    var mqttPassword: String?
        get() = encryptedPrefs.getString(KEY_MQTT_PASSWORD, null)
        set(value) = encryptedPrefs.edit().putString(KEY_MQTT_PASSWORD, value).apply()

    // =========================================================================
    // Server Configuration
    // =========================================================================

    var serverUrl: String?
        get() = encryptedPrefs.getString(KEY_SERVER_URL, null)
        set(value) = encryptedPrefs.edit().putString(KEY_SERVER_URL, value).apply()

    // =========================================================================
    // App Lock
    // =========================================================================

    var appPinHash: String?
        get() = encryptedPrefs.getString(KEY_APP_PIN_HASH, null)
        set(value) = encryptedPrefs.edit().putString(KEY_APP_PIN_HASH, value).apply()

    var biometricEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    fun hasAppLock(): Boolean = appPinHash != null || biometricEnabled

    // =========================================================================
    // App Whitelist
    // =========================================================================

    var whitelistedApps: Set<String>
        get() = encryptedPrefs.getStringSet(KEY_WHITELISTED_APPS, emptySet()) ?: emptySet()
        set(value) = encryptedPrefs.edit().putStringSet(KEY_WHITELISTED_APPS, value).apply()

    fun addWhitelistedApp(packageName: String) {
        val current = whitelistedApps.toMutableSet()
        current.add(packageName)
        whitelistedApps = current
        Log.i(TAG, "Added app to whitelist: $packageName")
    }

    fun removeWhitelistedApp(packageName: String) {
        val current = whitelistedApps.toMutableSet()
        current.remove(packageName)
        whitelistedApps = current
        Log.i(TAG, "Removed app from whitelist: $packageName")
    }

    fun isAppWhitelisted(packageName: String): Boolean {
        val whitelist = whitelistedApps
        // If whitelist is empty, allow all apps (for initial setup)
        // Once apps are added, only those are allowed
        return whitelist.isEmpty() || whitelist.contains(packageName)
    }

    // =========================================================================
    // Privacy & Consent
    // =========================================================================

    var privacyConsentGiven: Boolean
        get() = encryptedPrefs.getBoolean(KEY_PRIVACY_CONSENT_GIVEN, false)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_PRIVACY_CONSENT_GIVEN, value).apply()

    var privacyConsentDate: Long
        get() = encryptedPrefs.getLong(KEY_PRIVACY_CONSENT_DATE, 0)
        set(value) = encryptedPrefs.edit().putLong(KEY_PRIVACY_CONSENT_DATE, value).apply()

    var dataRetentionDays: Int
        get() = encryptedPrefs.getInt(KEY_DATA_RETENTION_DAYS, 7)
        set(value) = encryptedPrefs.edit().putInt(KEY_DATA_RETENTION_DAYS, value).apply()

    var auditLoggingEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_AUDIT_LOGGING_ENABLED, true)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_AUDIT_LOGGING_ENABLED, value).apply()

    fun givePrivacyConsent() {
        privacyConsentGiven = true
        privacyConsentDate = System.currentTimeMillis()
        Log.i(TAG, "Privacy consent recorded")
    }

    fun revokePrivacyConsent() {
        privacyConsentGiven = false
        privacyConsentDate = 0
        Log.i(TAG, "Privacy consent revoked")
    }

    // =========================================================================
    // Per-App Consent Storage
    // =========================================================================

    fun getConsentsJson(): String? = encryptedPrefs.getString(KEY_APP_CONSENTS, null)

    fun setConsentsJson(json: String) {
        encryptedPrefs.edit().putString(KEY_APP_CONSENTS, json).apply()
    }

    var sensitiveDetectionEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_SENSITIVE_DETECTION_ENABLED, true)
        set(value) = encryptedPrefs.edit().putBoolean(KEY_SENSITIVE_DETECTION_ENABLED, value).apply()

    // =========================================================================
    // Navigation Learning
    // =========================================================================

    /**
     * Enable/disable navigation learning
     * Default: false (privacy-first, user must opt-in)
     */
    var navigationLearningEnabled: Boolean
        get() = encryptedPrefs.getBoolean(KEY_NAVIGATION_LEARNING_ENABLED, false)
        set(value) {
            encryptedPrefs.edit().putBoolean(KEY_NAVIGATION_LEARNING_ENABLED, value).apply()
            Log.i(TAG, "Navigation learning ${if (value) "enabled" else "disabled"}")
        }

    /**
     * Navigation learning mode
     * PASSIVE: Learn while user interacts with apps normally
     * EXECUTION_ONLY: Only learn during flow execution
     * Default: EXECUTION_ONLY (more privacy-conscious)
     */
    var navigationLearningMode: NavigationLearningMode
        get() {
            val modeStr = encryptedPrefs.getString(KEY_NAVIGATION_LEARNING_MODE, "EXECUTION_ONLY")
            return try {
                NavigationLearningMode.valueOf(modeStr ?: "EXECUTION_ONLY")
            } catch (e: Exception) {
                NavigationLearningMode.EXECUTION_ONLY
            }
        }
        set(value) {
            encryptedPrefs.edit().putString(KEY_NAVIGATION_LEARNING_MODE, value.name).apply()
            Log.i(TAG, "Navigation learning mode set to: ${value.name}")
        }

    // =========================================================================
    // Data Management
    // =========================================================================

    /**
     * Clear all stored data (for privacy/uninstall)
     */
    fun clearAllData() {
        encryptedPrefs.edit().clear().apply()
        Log.i(TAG, "All secure preferences cleared")
    }

    /**
     * Export settings (without sensitive data) for backup
     */
    fun exportNonSensitiveSettings(): Map<String, Any?> {
        return mapOf(
            "mqtt_broker" to mqttBroker,
            "mqtt_port" to mqttPort,
            "server_url" to serverUrl,
            "whitelisted_apps" to whitelistedApps.toList(),
            "data_retention_days" to dataRetentionDays,
            "audit_logging_enabled" to auditLoggingEnabled
            // Note: credentials, PIN hash NOT exported
        )
    }
}
