package com.visualmapper.companion.security

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Sensitive Data Detector
 *
 * Identifies UI elements containing sensitive information that should NOT
 * be captured or transmitted without explicit user consent.
 *
 * Categories of sensitive data:
 * 1. Password/PIN fields
 * 2. Financial information (account numbers, card numbers)
 * 3. Personal identifiable information (SSN, ID numbers)
 * 4. Medical/health data
 * 5. Authentication tokens/codes
 *
 * Privacy principles:
 * - Detect BEFORE capturing (not after)
 * - Never transmit sensitive data without consent
 * - Mask sensitive fields in logs/previews
 * - Allow user to configure sensitivity levels
 */
class SensitiveDataDetector {

    companion object {
        private const val TAG = "SensitiveDataDetector"

        // Known sensitive app package patterns
        private val SENSITIVE_APP_PATTERNS = listOf(
            // Banking apps
            "com.chase", "com.wellsfargo", "com.bankofamerica", "com.citi",
            "com.usbank", "com.capitalone", "com.ally", "com.discover",
            "com.paypal", "com.venmo", "com.squareup.cash", "com.zellepay",
            // Password managers
            "com.lastpass", "com.onepassword", "com.dashlane", "com.bitwarden",
            "com.keepass", "com.nordpass", "com.roboform",
            // Authentication apps
            "com.google.android.apps.authenticator", "com.authy",
            "com.microsoft.authenticator", "com.duo",
            // Medical/Health
            "com.myfitnesspal", "com.fitbit", "com.garmin",
            // Messaging (contain private conversations)
            "com.whatsapp", "org.telegram", "com.facebook.orca",
            "com.Slack", "com.discord",
            // Email
            "com.google.android.gm", "com.microsoft.office.outlook",
            // Crypto wallets
            "com.coinbase", "com.binance", "piuk.blockchain"
        )

        // Resource ID patterns indicating sensitive fields
        private val SENSITIVE_ID_PATTERNS = listOf(
            "password", "passwd", "pin", "passcode", "secret",
            "ssn", "social_security", "tax_id",
            "card_number", "cvv", "cvc", "expiry", "card_exp",
            "account_number", "routing_number", "bank_account",
            "credit_card", "debit_card",
            "otp", "verification_code", "auth_code", "2fa",
            "private_key", "seed_phrase", "recovery_phrase",
            "medical_id", "health_id", "patient_id"
        )

        // Text patterns that indicate sensitive content
        private val SENSITIVE_TEXT_PATTERNS = listOf(
            Regex("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"), // Credit card
            Regex("\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b"), // SSN
            Regex("\\b\\d{9,10}\\b"), // Account numbers
            Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}([A-Z0-9]?){0,16}\\b"), // IBAN
            Regex("\\b\\d{6,8}\\b") // OTP codes (6-8 digits)
        )

        // Hint text patterns
        private val SENSITIVE_HINT_PATTERNS = listOf(
            "password", "pin", "passcode", "ssn", "social security",
            "card number", "cvv", "account number", "routing",
            "verification code", "otp", "one-time", "secret"
        )
    }

    data class SensitiveDataResult(
        val isSensitive: Boolean,
        val reason: String?,
        val category: SensitiveCategory?,
        val confidence: Float, // 0.0 to 1.0
        val recommendation: String?
    )

    enum class SensitiveCategory {
        PASSWORD,
        FINANCIAL,
        PERSONAL_ID,
        MEDICAL,
        AUTHENTICATION,
        PRIVATE_MESSAGE,
        UNKNOWN
    }

    // =========================================================================
    // App-Level Detection
    // =========================================================================

    /**
     * Check if an app is in the known sensitive apps list
     */
    fun isSensitiveApp(packageName: String): SensitiveDataResult {
        val matchedPattern = SENSITIVE_APP_PATTERNS.find { pattern ->
            packageName.lowercase().contains(pattern.lowercase())
        }

        return if (matchedPattern != null) {
            val category = categorizeAppByPackage(packageName)
            SensitiveDataResult(
                isSensitive = true,
                reason = "App matches sensitive pattern: $matchedPattern",
                category = category,
                confidence = 0.9f,
                recommendation = "Request explicit consent before capturing data from this app"
            )
        } else {
            SensitiveDataResult(
                isSensitive = false,
                reason = null,
                category = null,
                confidence = 0.0f,
                recommendation = null
            )
        }
    }

    private fun categorizeAppByPackage(packageName: String): SensitiveCategory {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("bank") || pkg.contains("paypal") || pkg.contains("venmo") ||
            pkg.contains("cash") || pkg.contains("coinbase") || pkg.contains("binance") ->
                SensitiveCategory.FINANCIAL

            pkg.contains("password") || pkg.contains("lastpass") || pkg.contains("1password") ||
            pkg.contains("bitwarden") || pkg.contains("keepass") ->
                SensitiveCategory.PASSWORD

            pkg.contains("authenticator") || pkg.contains("authy") || pkg.contains("duo") ->
                SensitiveCategory.AUTHENTICATION

            pkg.contains("fitness") || pkg.contains("health") || pkg.contains("medical") ->
                SensitiveCategory.MEDICAL

            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messenger") ||
            pkg.contains("slack") || pkg.contains("discord") ->
                SensitiveCategory.PRIVATE_MESSAGE

            else -> SensitiveCategory.UNKNOWN
        }
    }

    // =========================================================================
    // Node-Level Detection
    // =========================================================================

    /**
     * Check if an accessibility node contains sensitive data
     */
    fun checkNode(node: AccessibilityNodeInfo?): SensitiveDataResult {
        if (node == null) {
            return SensitiveDataResult(false, null, null, 0.0f, null)
        }

        // Check 1: Is it a password field?
        if (node.isPassword) {
            return SensitiveDataResult(
                isSensitive = true,
                reason = "Password input field",
                category = SensitiveCategory.PASSWORD,
                confidence = 1.0f,
                recommendation = "Never capture password field content"
            )
        }

        // Check 2: Resource ID patterns
        val resourceId = node.viewIdResourceName ?: ""
        val matchedIdPattern = SENSITIVE_ID_PATTERNS.find { pattern ->
            resourceId.lowercase().contains(pattern)
        }
        if (matchedIdPattern != null) {
            return SensitiveDataResult(
                isSensitive = true,
                reason = "Resource ID contains sensitive pattern: $matchedIdPattern",
                category = categorizeByIdPattern(matchedIdPattern),
                confidence = 0.85f,
                recommendation = "Mask this field or request consent"
            )
        }

        // Check 3: Hint text patterns
        val hintText = node.hintText?.toString()?.lowercase() ?: ""
        val matchedHint = SENSITIVE_HINT_PATTERNS.find { pattern ->
            hintText.contains(pattern)
        }
        if (matchedHint != null) {
            return SensitiveDataResult(
                isSensitive = true,
                reason = "Hint text indicates sensitive field: $matchedHint",
                category = categorizeByIdPattern(matchedHint),
                confidence = 0.8f,
                recommendation = "Mask this field or request consent"
            )
        }

        // Check 4: Content description patterns
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val matchedDesc = SENSITIVE_HINT_PATTERNS.find { pattern ->
            contentDesc.contains(pattern)
        }
        if (matchedDesc != null) {
            return SensitiveDataResult(
                isSensitive = true,
                reason = "Content description indicates sensitive: $matchedDesc",
                category = categorizeByIdPattern(matchedDesc),
                confidence = 0.75f,
                recommendation = "Mask this field or request consent"
            )
        }

        // Check 5: Text content patterns (only for non-editable fields)
        if (!node.isEditable) {
            val text = node.text?.toString() ?: ""
            val matchedTextPattern = SENSITIVE_TEXT_PATTERNS.find { pattern ->
                pattern.containsMatchIn(text)
            }
            if (matchedTextPattern != null) {
                return SensitiveDataResult(
                    isSensitive = true,
                    reason = "Text content matches sensitive pattern",
                    category = SensitiveCategory.FINANCIAL, // Most text patterns are financial
                    confidence = 0.7f,
                    recommendation = "Mask or redact this content"
                )
            }
        }

        return SensitiveDataResult(
            isSensitive = false,
            reason = null,
            category = null,
            confidence = 0.0f,
            recommendation = null
        )
    }

    private fun categorizeByIdPattern(pattern: String): SensitiveCategory {
        return when {
            pattern.contains("password") || pattern.contains("pin") ||
            pattern.contains("passcode") || pattern.contains("secret") ->
                SensitiveCategory.PASSWORD

            pattern.contains("card") || pattern.contains("account") ||
            pattern.contains("routing") || pattern.contains("cvv") ->
                SensitiveCategory.FINANCIAL

            pattern.contains("ssn") || pattern.contains("social") ||
            pattern.contains("tax") ->
                SensitiveCategory.PERSONAL_ID

            pattern.contains("otp") || pattern.contains("verification") ||
            pattern.contains("auth") || pattern.contains("2fa") ->
                SensitiveCategory.AUTHENTICATION

            pattern.contains("medical") || pattern.contains("health") ||
            pattern.contains("patient") ->
                SensitiveCategory.MEDICAL

            else -> SensitiveCategory.UNKNOWN
        }
    }

    // =========================================================================
    // UI Tree Scanning
    // =========================================================================

    /**
     * Scan entire UI tree for sensitive content
     * Returns list of all sensitive nodes found
     */
    fun scanUITree(rootNode: AccessibilityNodeInfo?): List<Pair<AccessibilityNodeInfo, SensitiveDataResult>> {
        val sensitiveNodes = mutableListOf<Pair<AccessibilityNodeInfo, SensitiveDataResult>>()

        if (rootNode == null) return sensitiveNodes

        scanNodeRecursive(rootNode, sensitiveNodes)
        return sensitiveNodes
    }

    private fun scanNodeRecursive(
        node: AccessibilityNodeInfo,
        results: MutableList<Pair<AccessibilityNodeInfo, SensitiveDataResult>>
    ) {
        val result = checkNode(node)
        if (result.isSensitive) {
            results.add(Pair(node, result))
        }

        // Scan children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                scanNodeRecursive(child, results)
                // CRITICAL FIX: Recycle child node to prevent resource exhaustion
                try { child.recycle() } catch (e: Exception) { /* Already recycled */ }
            }
        }
    }

    /**
     * Get a sanitized/masked version of text if sensitive
     */
    fun maskSensitiveText(text: String, result: SensitiveDataResult): String {
        if (!result.isSensitive) return text

        return when (result.category) {
            SensitiveCategory.PASSWORD -> "••••••••"
            SensitiveCategory.FINANCIAL -> {
                // Show last 4 digits of card/account numbers
                if (text.length > 4) {
                    "••••" + text.takeLast(4)
                } else {
                    "••••"
                }
            }
            SensitiveCategory.PERSONAL_ID -> "•••-••-" + text.takeLast(4)
            SensitiveCategory.AUTHENTICATION -> "••••••"
            else -> "••••••••"
        }
    }

    /**
     * Check if capture should be blocked entirely
     */
    fun shouldBlockCapture(result: SensitiveDataResult): Boolean {
        // Always block password fields
        if (result.category == SensitiveCategory.PASSWORD && result.confidence >= 0.9f) {
            return true
        }
        // Block high-confidence sensitive content
        return result.confidence >= 0.95f
    }
}
