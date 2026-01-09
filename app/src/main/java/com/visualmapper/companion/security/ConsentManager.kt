package com.visualmapper.companion.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Consent Manager
 *
 * Manages user consent for data collection from specific apps.
 * GDPR/CCPA inspired approach:
 * - Explicit consent required before collecting from sensitive apps
 * - Consent can be revoked at any time
 * - Clear explanation of what data is collected
 * - Audit trail of consent decisions
 *
 * Consent Levels:
 * 1. NONE - Never collect from this app
 * 2. BASIC - Only non-sensitive elements (buttons, labels)
 * 3. FULL - All UI elements including text content
 * 4. SENSITIVE - Allow even password hints (but never actual passwords)
 */
class ConsentManager(
    private val context: Context,
    private val securePrefs: SecurePreferences,
    private val auditLogger: AuditLogger
) {
    companion object {
        private const val TAG = "ConsentManager"
        private const val PREF_KEY_CONSENTS = "app_consents"
    }

    @Serializable
    data class AppConsent(
        val packageName: String,
        val level: ConsentLevel,
        val grantedAt: Long,
        val expiresAt: Long?, // null = never expires
        val dataCategories: List<String>, // What data user consented to
        val purpose: String // Why we need access
    )

    enum class ConsentLevel {
        NONE,      // No access allowed
        BASIC,     // Only UI structure, no text content
        FULL,      // All visible text
        SENSITIVE  // Include sensitive field hints (never actual values)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val _consents = MutableStateFlow<Map<String, AppConsent>>(emptyMap())
    val consents: StateFlow<Map<String, AppConsent>> = _consents

    init {
        loadConsents()
    }

    // =========================================================================
    // Consent Management
    // =========================================================================

    /**
     * Request consent for an app - returns consent request details
     */
    fun createConsentRequest(
        packageName: String,
        appName: String,
        isSensitiveApp: Boolean
    ): ConsentRequest {
        val dataCategories = mutableListOf(
            "UI element positions and bounds",
            "Button and clickable element text",
            "Screen layout structure"
        )

        val warnings = mutableListOf<String>()

        if (!isSensitiveApp) {
            dataCategories.add("Text content from labels and fields")
        } else {
            warnings.add("This app may contain sensitive financial or personal data")
            warnings.add("Password and credit card fields will NEVER be captured")
            warnings.add("Only UI structure will be collected by default")
        }

        return ConsentRequest(
            packageName = packageName,
            appName = appName,
            dataCategories = dataCategories,
            warnings = warnings,
            recommendedLevel = if (isSensitiveApp) ConsentLevel.BASIC else ConsentLevel.FULL,
            isSensitiveApp = isSensitiveApp
        )
    }

    data class ConsentRequest(
        val packageName: String,
        val appName: String,
        val dataCategories: List<String>,
        val warnings: List<String>,
        val recommendedLevel: ConsentLevel,
        val isSensitiveApp: Boolean
    )

    /**
     * Grant consent for an app
     */
    fun grantConsent(
        packageName: String,
        level: ConsentLevel,
        expiryDays: Int? = null, // null = no expiry
        dataCategories: List<String> = emptyList(),
        purpose: String = "Visual automation and monitoring"
    ) {
        val consent = AppConsent(
            packageName = packageName,
            level = level,
            grantedAt = System.currentTimeMillis(),
            expiresAt = expiryDays?.let {
                System.currentTimeMillis() + (it * 24 * 60 * 60 * 1000L)
            },
            dataCategories = dataCategories,
            purpose = purpose
        )

        val updated = _consents.value.toMutableMap()
        updated[packageName] = consent
        _consents.value = updated
        saveConsents()

        auditLogger.logConsentChange(
            packageName = packageName,
            action = "GRANTED",
            level = level.name,
            details = "Consent granted for ${dataCategories.size} data categories"
        )

        Log.i(TAG, "Consent granted for $packageName at level $level")
    }

    /**
     * Revoke consent for an app
     */
    fun revokeConsent(packageName: String) {
        val previousConsent = _consents.value[packageName]

        val updated = _consents.value.toMutableMap()
        updated.remove(packageName)
        _consents.value = updated
        saveConsents()

        auditLogger.logConsentChange(
            packageName = packageName,
            action = "REVOKED",
            level = previousConsent?.level?.name ?: "NONE",
            details = "User revoked consent"
        )

        Log.i(TAG, "Consent revoked for $packageName")
    }

    /**
     * Check if we have valid consent for an app
     */
    fun hasValidConsent(packageName: String): Boolean {
        val consent = _consents.value[packageName] ?: return false

        // Check if expired
        consent.expiresAt?.let { expiry ->
            if (System.currentTimeMillis() > expiry) {
                Log.d(TAG, "Consent expired for $packageName")
                return false
            }
        }

        return consent.level != ConsentLevel.NONE
    }

    /**
     * Get consent level for an app
     */
    fun getConsentLevel(packageName: String): ConsentLevel {
        val consent = _consents.value[packageName] ?: return ConsentLevel.NONE

        // Check expiry
        consent.expiresAt?.let { expiry ->
            if (System.currentTimeMillis() > expiry) {
                return ConsentLevel.NONE
            }
        }

        return consent.level
    }

    /**
     * Check what data we can collect based on consent
     */
    fun canCollect(
        packageName: String,
        dataType: DataType
    ): Boolean {
        val level = getConsentLevel(packageName)

        return when (dataType) {
            DataType.UI_STRUCTURE -> level >= ConsentLevel.BASIC
            DataType.BUTTON_TEXT -> level >= ConsentLevel.BASIC
            DataType.LABEL_TEXT -> level >= ConsentLevel.FULL
            DataType.INPUT_TEXT -> level >= ConsentLevel.FULL
            DataType.SENSITIVE_HINTS -> level >= ConsentLevel.SENSITIVE
            DataType.PASSWORD_VALUES -> false // NEVER allowed
            DataType.FINANCIAL_VALUES -> false // NEVER allowed
        }
    }

    enum class DataType {
        UI_STRUCTURE,      // Element positions, bounds, types
        BUTTON_TEXT,       // Text on buttons/clickables
        LABEL_TEXT,        // Static text labels
        INPUT_TEXT,        // Non-sensitive input field content
        SENSITIVE_HINTS,   // Hints like "Enter password"
        PASSWORD_VALUES,   // Actual password content (NEVER)
        FINANCIAL_VALUES   // Credit card numbers, etc (NEVER)
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private fun loadConsents() {
        try {
            val jsonString = securePrefs.getConsentsJson()
            if (jsonString != null) {
                val list: List<AppConsent> = json.decodeFromString(jsonString)
                _consents.value = list.associateBy { it.packageName }
                Log.d(TAG, "Loaded ${list.size} app consents")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load consents", e)
        }
    }

    private fun saveConsents() {
        try {
            val list = _consents.value.values.toList()
            val jsonString = json.encodeToString(list)
            securePrefs.setConsentsJson(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save consents", e)
        }
    }

    // =========================================================================
    // Bulk Operations
    // =========================================================================

    /**
     * Get all apps with consent
     */
    fun getAllConsents(): List<AppConsent> = _consents.value.values.toList()

    /**
     * Revoke all consents (privacy reset)
     */
    fun revokeAllConsents() {
        val count = _consents.value.size
        _consents.value = emptyMap()
        saveConsents()

        auditLogger.logConsentChange(
            packageName = "*",
            action = "REVOKED_ALL",
            level = "ALL",
            details = "User revoked all $count consents"
        )

        Log.i(TAG, "All consents revoked")
    }

    /**
     * Export consent data for user review
     */
    fun exportConsentReport(): String {
        val report = StringBuilder()
        report.appendLine("=== Visual Mapper Consent Report ===")
        report.appendLine("Generated: ${java.util.Date()}")
        report.appendLine()

        for (consent in _consents.value.values) {
            report.appendLine("App: ${consent.packageName}")
            report.appendLine("  Level: ${consent.level}")
            report.appendLine("  Granted: ${java.util.Date(consent.grantedAt)}")
            consent.expiresAt?.let {
                report.appendLine("  Expires: ${java.util.Date(it)}")
            }
            report.appendLine("  Data Categories: ${consent.dataCategories.joinToString(", ")}")
            report.appendLine("  Purpose: ${consent.purpose}")
            report.appendLine()
        }

        return report.toString()
    }
}
