package com.visualmapper.companion.explorer

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Detects sensitive apps that should not be explored.
 *
 * Sensitive apps include:
 * - Banking & Finance apps
 * - Messaging & Email apps
 * - Password managers
 * - Health apps
 * - Social media with personal content
 *
 * Extracted from AppExplorerService for modularity.
 */
object SensitiveAppDetector {

    private const val TAG = "SensitiveAppDetector"

    // =========================================================================
    // SENSITIVE APP BLACKLIST - Never explore these apps
    // =========================================================================

    private val SENSITIVE_APP_PACKAGES = setOf(
        // Email apps - contain personal communications
        "com.google.android.gm",            // Gmail
        "com.microsoft.office.outlook",     // Outlook
        "com.yahoo.mobile.client.android.mail",  // Yahoo Mail
        "com.samsung.android.email.provider", // Samsung Email
        "com.aol.mobile.aolapp",            // AOL Mail
        "com.zoho.mail",                    // Zoho Mail
        "me.bluemail.mail",                 // BlueMail
        "com.easilydo.mail",                // Edison Mail
        "org.mozilla.thunderbird",          // Thunderbird
        "com.readdle.spark",                // Spark

        // Messaging apps - personal conversations
        "com.whatsapp",                     // WhatsApp
        "org.telegram.messenger",           // Telegram
        "com.facebook.orca",                // Messenger
        "com.Slack",                        // Slack
        "com.discord",                      // Discord
        "com.viber.voip",                   // Viber
        "jp.naver.line.android",            // LINE
        "com.snapchat.android",             // Snapchat
        "com.tencent.mm",                   // WeChat
        "com.google.android.apps.messaging", // Google Messages
        "com.samsung.android.messaging",    // Samsung Messages
        "com.skype.raider",                 // Skype
        "us.zoom.videomeetings",            // Zoom

        // Banking & Finance - highly sensitive
        "com.paypal.android.p2pmobile",     // PayPal
        "com.venmo",                        // Venmo
        "com.squareup.cash",                // Cash App
        "com.zellepay.zelle",               // Zelle
        "com.coinbase.android",             // Coinbase
        "com.binance.dev",                  // Binance
        "com.robinhood.android",            // Robinhood
        "com.chase.sig.android",            // Chase
        "com.wf.wellsfargomobile",          // Wells Fargo
        "com.infonow.bofa",                 // Bank of America
        "com.citi.citimobile",              // Citibank
        "com.usaa.mobile.android.usaa",     // USAA
        "com.capitalone.mobile",            // Capital One
        "com.discover.mobile",              // Discover
        "com.ally.mobilebanking",           // Ally

        // Social Media - personal profiles/posts
        "com.facebook.katana",              // Facebook
        "com.instagram.android",            // Instagram
        "com.twitter.android",              // X/Twitter
        "com.linkedin.android",             // LinkedIn
        "com.reddit.frontpage",             // Reddit
        "com.zhiliaoapp.musically",         // TikTok
        "com.pinterest",                    // Pinterest

        // Health apps - medical data
        "com.google.android.apps.fitness", // Google Fit
        "com.samsung.android.shealth",     // Samsung Health
        "com.myfitnesspal.android",        // MyFitnessPal
        "com.fitbit.FitbitMobile",         // Fitbit

        // Password managers - NEVER access
        "com.lastpass.lpandroid",          // LastPass
        "com.onepassword.android",         // 1Password
        "com.dashlane",                    // Dashlane
        "keepass.android",                 // KeePass
        "com.bitwarden.android",           // Bitwarden

        // System/Security apps - NEVER access
        "com.android.settings",            // Settings - could change system config!
        "com.samsung.android.settings",    // Samsung Settings
        "com.google.android.gms"           // Google Play Services (system, not user app)
    )

    // Patterns to match sensitive apps by package name
    private val SENSITIVE_APP_PATTERNS = listOf(
        "bank", "banking", "finance", "wallet", "credit",
        "payment", "pay.", ".pay", "money", "invest",
        "stock", "trading", "crypto",
        "mail", "email", "message", "chat", "sms",
        "health", "medical", "fitness",
        "password", "vault", "secure", "authenticator", "2fa", "otp"
    )

    // Sensitive permissions that indicate personal data access
    private val SENSITIVE_PERMISSIONS = listOf(
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.GET_ACCOUNTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_FINGERPRINT"
    )

    // High-sensitivity permissions (even one = skip)
    private val HIGH_SENSITIVITY_PERMISSIONS = listOf(
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.BODY_SENSORS"
    )

    // Apps that are SAFE to explore even if they have some sensitive permissions
    private val SAFE_APPS_WHITELIST = setOf(
        // Browsers
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.opera.browser",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",

        // Media apps
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "com.hulu.plus",
        "tv.twitch.android.app",
        "com.vimeo.android.videoapp",

        // Productivity
        "com.google.android.apps.maps",
        "com.google.android.apps.docs",
        "com.google.android.apps.photos",
        "com.google.android.keep",
        "com.google.android.calendar",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.microsoft.teams",
        "com.google.android.apps.tachyon",

        // Utilities
        "com.google.android.calculator",
        "com.sec.android.app.calculator",
        "com.android.calculator2",
        "com.google.android.deskclock",
        "com.sec.android.app.clockpackage",
        "com.android.camera",
        "com.sec.android.app.camera",
        "com.google.android.GoogleCamera",
        "com.google.android.apps.translate",
        "com.google.android.apps.fitness",

        // File managers
        "com.google.android.apps.nbu.files",
        "com.sec.android.app.myfiles",
        "com.mi.android.globalFileexplorer",

        // Gallery apps
        "com.sec.android.gallery3d",
        "com.google.android.apps.photosgo",

        // System apps
        "com.android.vending",
        "com.sec.android.app.launcher"
    )

    /**
     * Check if an app is sensitive (blacklisted) using static checks only.
     * Fast check without requiring context.
     *
     * @return true if app should be SKIPPED
     */
    fun isSensitiveApp(packageName: String): Boolean {
        // Check exact match in blacklist
        if (SENSITIVE_APP_PACKAGES.contains(packageName)) {
            Log.i(TAG, "App $packageName is in sensitive blacklist - SKIPPING")
            return true
        }

        // Check pattern match
        val lowerPackage = packageName.lowercase()
        for (pattern in SENSITIVE_APP_PATTERNS) {
            if (lowerPackage.contains(pattern)) {
                Log.i(TAG, "App $packageName matches sensitive pattern '$pattern' - SKIPPING")
                return true
            }
        }

        return false
    }

    /**
     * Check if an app is in the safe whitelist.
     */
    fun isSafeApp(packageName: String): Boolean {
        return SAFE_APPS_WHITELIST.contains(packageName)
    }

    /**
     * INTELLIGENT detection: Analyze app permissions and metadata
     * to determine if it handles sensitive data.
     *
     * @return true if app should be SKIPPED
     */
    fun isSensitiveAppIntelligent(context: Context, packageName: String): Boolean {
        // Check safe whitelist first
        if (isSafeApp(packageName)) {
            Log.d(TAG, "App $packageName is in safe whitelist - ALLOWING")
            return false
        }

        // Check static blacklists
        if (isSensitiveApp(packageName)) {
            return true
        }

        // Intelligent permission-based detection
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES
            )

            val permissions = packageInfo.requestedPermissions ?: emptyArray()

            // Check for high-sensitivity permissions (one is enough to skip)
            for (permission in HIGH_SENSITIVITY_PERMISSIONS) {
                if (permissions.contains(permission)) {
                    Log.i(TAG, "App $packageName has high-sensitivity permission: $permission - SKIPPING")
                    return true
                }
            }

            // Count total sensitive permissions
            var sensitiveCount = 0
            for (permission in SENSITIVE_PERMISSIONS) {
                if (permissions.contains(permission)) {
                    sensitiveCount++
                }
            }

            if (sensitiveCount >= 5) {
                val hasHighSensitivity = HIGH_SENSITIVITY_PERMISSIONS.any { permissions.contains(it) }
                if (hasHighSensitivity) {
                    Log.i(TAG, "App $packageName has $sensitiveCount sensitive permissions AND high-sensitivity - SKIPPING")
                    return true
                }
                Log.d(TAG, "App $packageName has $sensitiveCount sensitive permissions (no high-sensitivity, allowing)")
            }

            // Check app label for sensitive keywords
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appLabel = pm.getApplicationLabel(appInfo).toString().lowercase()
            val sensitiveKeywords = listOf(
                "bank", "banking", "finance", "money", "wallet", "pay", "payment",
                "mail", "email", "message", "chat", "sms", "text",
                "password", "vault", "secure", "authenticator", "2fa",
                "health", "medical", "fitness", "doctor",
                "dating", "social", "private", "secret"
            )

            for (keyword in sensitiveKeywords) {
                if (appLabel.contains(keyword)) {
                    Log.i(TAG, "App $packageName label '$appLabel' contains sensitive keyword '$keyword' - SKIPPING")
                    return true
                }
            }

            Log.d(TAG, "App $packageName passed all sensitivity checks - OK to explore")
            return false

        } catch (e: Exception) {
            Log.w(TAG, "Could not analyze app $packageName: ${e.message}")
            return isSensitiveApp(packageName)
        }
    }

    /**
     * Check if training mode is enabled (Allow All Apps)
     */
    fun isTrainingModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        return prefs.getBoolean("allow_all_apps_training", false)
    }
}
