package com.visualmapper.companion.explorer.learning

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores learned patterns for sensor detection, dangerous elements, and semantics.
 * Improves suggestions over time based on exploration results.
 */
class LearningStore(context: Context) {

    companion object {
        private const val TAG = "LearningStore"
        private const val PREFS_PATTERNS = "learned_patterns"
        private const val PREFS_DANGERS = "dangerous_elements"
        private const val PREFS_SEMANTICS = "semantic_context"
        private const val MAX_PATTERNS = 500
        private const val MAX_DANGERS = 200
        private const val MAX_SEMANTICS = 300

        @Volatile
        private var instance: LearningStore? = null

        fun getInstance(context: Context): LearningStore {
            return instance ?: synchronized(this) {
                instance ?: LearningStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val patternsPrefs = context.getSharedPreferences(PREFS_PATTERNS, Context.MODE_PRIVATE)
    private val dangersPrefs = context.getSharedPreferences(PREFS_DANGERS, Context.MODE_PRIVATE)
    private val semanticsPrefs = context.getSharedPreferences(PREFS_SEMANTICS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // In-memory caches
    private var cachedPatterns: MutableList<LearnedPattern>? = null
    private var cachedDangers: MutableList<DangerousElement>? = null
    private var cachedSemantics: MutableList<SemanticContext>? = null

    // ==================== Pattern Recognition ====================

    /**
     * Record a value pattern with its device class mapping
     */
    fun recordPattern(
        valuePattern: String,
        patternType: PatternType,
        suggestedDeviceClass: String,
        suggestedUnit: String? = null,
        appPackage: String? = null
    ) {
        val patterns = getAllPatterns().toMutableList()

        // Check for existing pattern
        val existing = patterns.find {
            it.valuePattern == valuePattern && it.patternType == patternType
        }

        if (existing != null) {
            // Update confidence
            val updated = existing.copy(
                confidence = minOf(1.0f, existing.confidence + 0.1f),
                occurrences = existing.occurrences + 1
            )
            patterns.remove(existing)
            patterns.add(updated)
        } else {
            patterns.add(LearnedPattern(
                valuePattern = valuePattern,
                patternType = patternType,
                regex = generateRegex(patternType),
                suggestedDeviceClass = suggestedDeviceClass,
                suggestedUnit = suggestedUnit,
                appPackage = appPackage,
                confidence = 0.6f,
                occurrences = 1
            ))
        }

        savePatterns(patterns.takeLast(MAX_PATTERNS))
        Log.d(TAG, "Recorded pattern: $patternType -> $suggestedDeviceClass")
    }

    /**
     * Get suggested device class for a value
     */
    fun suggestDeviceClass(value: String, appPackage: String? = null): PatternSuggestion? {
        val patterns = getAllPatterns()

        // Check app-specific patterns first
        if (appPackage != null) {
            val appPattern = patterns.find {
                it.appPackage == appPackage && matchesPattern(value, it)
            }
            if (appPattern != null) {
                return PatternSuggestion(
                    deviceClass = appPattern.suggestedDeviceClass,
                    unit = appPattern.suggestedUnit,
                    confidence = appPattern.confidence,
                    patternType = appPattern.patternType
                )
            }
        }

        // Check global patterns
        val globalPattern = patterns.filter { it.appPackage == null }
            .sortedByDescending { it.confidence }
            .find { matchesPattern(value, it) }

        if (globalPattern != null) {
            return PatternSuggestion(
                deviceClass = globalPattern.suggestedDeviceClass,
                unit = globalPattern.suggestedUnit,
                confidence = globalPattern.confidence,
                patternType = globalPattern.patternType
            )
        }

        // Try built-in pattern detection
        return detectBuiltInPattern(value)
    }

    /**
     * Detect pattern type from value using built-in rules
     */
    private fun detectBuiltInPattern(value: String): PatternSuggestion? {
        val trimmed = value.trim()

        return when {
            // Percentage patterns
            trimmed.matches(Regex("""^\d+\s*%$""")) -> PatternSuggestion(
                deviceClass = "battery", // Default, could also be humidity
                unit = "%",
                confidence = 0.7f,
                patternType = PatternType.PERCENTAGE
            )

            // Temperature patterns
            trimmed.matches(Regex("""^-?\d+(?:\.\d+)?\s*°[CF]$""")) -> PatternSuggestion(
                deviceClass = "temperature",
                unit = if (trimmed.contains("°F")) "°F" else "°C",
                confidence = 0.9f,
                patternType = PatternType.TEMPERATURE
            )

            // Distance patterns
            trimmed.matches(Regex("""^\d+(?:[.,]\d+)?\s*(?:km|mi|m|ft|miles?)$""", RegexOption.IGNORE_CASE)) -> PatternSuggestion(
                deviceClass = "distance",
                unit = extractUnit(trimmed),
                confidence = 0.85f,
                patternType = PatternType.DISTANCE
            )

            // Speed patterns
            trimmed.matches(Regex("""^\d+(?:\.\d+)?\s*(?:mph|km/h|m/s|kph)$""", RegexOption.IGNORE_CASE)) -> PatternSuggestion(
                deviceClass = "speed",
                unit = extractUnit(trimmed),
                confidence = 0.9f,
                patternType = PatternType.SPEED
            )

            // Currency patterns
            trimmed.matches(Regex("""^[\$€£¥]\s*\d+(?:[.,]\d{2})?$""")) ||
            trimmed.matches(Regex("""^\d+(?:[.,]\d{2})?\s*[\$€£¥]$""")) -> PatternSuggestion(
                deviceClass = "monetary",
                unit = extractCurrency(trimmed),
                confidence = 0.85f,
                patternType = PatternType.CURRENCY
            )

            // Time duration patterns
            trimmed.matches(Regex("""^\d+\s*(?:h|hr|hrs?|hours?)\s*(?:\d+\s*(?:m|min|mins?|minutes?))?$""", RegexOption.IGNORE_CASE)) ||
            trimmed.matches(Regex("""^\d+\s*(?:m|min|mins?|minutes?)$""", RegexOption.IGNORE_CASE)) -> PatternSuggestion(
                deviceClass = "duration",
                unit = "min",
                confidence = 0.8f,
                patternType = PatternType.TIME_DURATION
            )

            // Data size patterns
            trimmed.matches(Regex("""^\d+(?:\.\d+)?\s*(?:KB|MB|GB|TB|B|bytes?)$""", RegexOption.IGNORE_CASE)) -> PatternSuggestion(
                deviceClass = "data_size",
                unit = extractUnit(trimmed).uppercase(),
                confidence = 0.9f,
                patternType = PatternType.DATA_SIZE
            )

            // Power/Energy patterns
            trimmed.matches(Regex("""^\d+(?:\.\d+)?\s*(?:W|kW|MW|Wh|kWh|MWh)$""", RegexOption.IGNORE_CASE)) -> PatternSuggestion(
                deviceClass = if (trimmed.contains("h", ignoreCase = true)) "energy" else "power",
                unit = extractUnit(trimmed),
                confidence = 0.9f,
                patternType = PatternType.POWER_ENERGY
            )

            // Voltage patterns
            trimmed.matches(Regex("""^\d+(?:\.\d+)?\s*(?:V|mV|volts?)$""", RegexOption.IGNORE_CASE)) -> PatternSuggestion(
                deviceClass = "voltage",
                unit = "V",
                confidence = 0.9f,
                patternType = PatternType.VOLTAGE
            )

            else -> null
        }
    }

    private fun matchesPattern(value: String, pattern: LearnedPattern): Boolean {
        if (pattern.regex != null) {
            return value.matches(Regex(pattern.regex, RegexOption.IGNORE_CASE))
        }
        // Simple contains check for value pattern
        return value.contains(pattern.valuePattern, ignoreCase = true)
    }

    private fun generateRegex(patternType: PatternType): String? {
        return when (patternType) {
            PatternType.PERCENTAGE -> """^\d+\s*%$"""
            PatternType.TEMPERATURE -> """^-?\d+(?:\.\d+)?\s*°[CF]$"""
            PatternType.DISTANCE -> """^\d+(?:[.,]\d+)?\s*(?:km|mi|m|ft)$"""
            PatternType.SPEED -> """^\d+(?:\.\d+)?\s*(?:mph|km/h|m/s)$"""
            PatternType.CURRENCY -> """^[\$€£¥]?\d+(?:[.,]\d{2})?[\$€£¥]?$"""
            PatternType.TIME_DURATION -> """^\d+\s*(?:h|m|hr|min)"""
            PatternType.DATA_SIZE -> """^\d+(?:\.\d+)?\s*(?:KB|MB|GB|TB)$"""
            PatternType.POWER_ENERGY -> """^\d+(?:\.\d+)?\s*(?:W|kW|Wh|kWh)$"""
            PatternType.VOLTAGE -> """^\d+(?:\.\d+)?\s*(?:V|mV)$"""
            PatternType.SIGNAL_BARS -> null // Visual detection, not regex
            PatternType.CUSTOM -> null
        }
    }

    private fun extractUnit(value: String): String {
        return value.replace(Regex("""[\d.,\s]+"""), "").trim()
    }

    private fun extractCurrency(value: String): String {
        return when {
            value.contains("$") -> "$"
            value.contains("€") -> "€"
            value.contains("£") -> "£"
            value.contains("¥") -> "¥"
            else -> ""
        }
    }

    private fun getAllPatterns(): List<LearnedPattern> {
        cachedPatterns?.let { return it }

        return try {
            val jsonStr = patternsPrefs.getString("patterns", null) ?: return emptyList()
            val list = json.decodeFromString<List<LearnedPattern>>(jsonStr)
            cachedPatterns = list.toMutableList()
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading patterns", e)
            emptyList()
        }
    }

    private fun savePatterns(patterns: List<LearnedPattern>) {
        cachedPatterns = patterns.toMutableList()
        patternsPrefs.edit()
            .putString("patterns", json.encodeToString(patterns))
            .apply()
    }

    // ==================== Dangerous Elements ====================

    /**
     * Record a dangerous element (logout, delete, etc.)
     */
    fun recordDangerousElement(
        appPackage: String,
        screenId: String,
        elementPattern: String, // text or resource-id pattern
        dangerType: DangerType,
        consequences: String? = null
    ) {
        val dangers = getAllDangers().toMutableList()

        // Check for existing
        val existing = dangers.find {
            it.appPackage == appPackage && it.elementPattern == elementPattern
        }

        if (existing == null) {
            dangers.add(DangerousElement(
                appPackage = appPackage,
                screenId = screenId,
                elementPattern = elementPattern,
                dangerType = dangerType,
                consequences = consequences ?: getDangerConsequences(dangerType),
                recordedAt = System.currentTimeMillis()
            ))
            saveDangers(dangers.takeLast(MAX_DANGERS))
            Log.d(TAG, "Recorded dangerous element: $elementPattern ($dangerType)")
        }
    }

    /**
     * Check if an element is known to be dangerous
     */
    fun isDangerous(appPackage: String, elementText: String?, resourceId: String?): DangerousElement? {
        val dangers = getAllDangers().filter { it.appPackage == appPackage }

        return dangers.find { danger ->
            (elementText != null && elementText.contains(danger.elementPattern, ignoreCase = true)) ||
            (resourceId != null && resourceId.contains(danger.elementPattern, ignoreCase = true))
        }
    }

    /**
     * Check if element text matches common dangerous patterns
     */
    fun detectDangerFromText(text: String): DangerType? {
        val lowerText = text.lowercase()
        return when {
            lowerText.matches(Regex(""".*\b(log\s*out|sign\s*out|exit|leave)\b.*""")) -> DangerType.LOGOUT
            lowerText.matches(Regex(""".*\b(delete|remove|erase|clear\s*all)\b.*""")) -> DangerType.DELETE
            lowerText.matches(Regex(""".*\b(reset|factory|restore\s*default)\b.*""")) -> DangerType.RESET
            lowerText.matches(Regex(""".*\b(close|quit|terminate)\b.*""")) -> DangerType.CLOSE_APP
            lowerText.matches(Regex(""".*\b(purchase|buy|pay|subscribe)\b.*""")) -> DangerType.PURCHASE
            lowerText.matches(Regex(""".*\b(open\s*in|share|external)\b.*""")) -> DangerType.EXTERNAL_LINK
            lowerText.matches(Regex(""".*\b(revoke|deny|disable\s*permission)\b.*""")) -> DangerType.PERMISSION_REVOKE
            else -> null
        }
    }

    private fun getDangerConsequences(dangerType: DangerType): String {
        return when (dangerType) {
            DangerType.LOGOUT -> "May log out of the app, losing session"
            DangerType.DELETE -> "May delete user data"
            DangerType.RESET -> "May reset app settings"
            DangerType.CLOSE_APP -> "May close or minimize the app"
            DangerType.PURCHASE -> "May trigger in-app purchase"
            DangerType.EXTERNAL_LINK -> "May open browser or another app"
            DangerType.PERMISSION_REVOKE -> "May remove accessibility permissions"
        }
    }

    private fun getAllDangers(): List<DangerousElement> {
        cachedDangers?.let { return it }

        return try {
            val jsonStr = dangersPrefs.getString("dangers", null) ?: return emptyList()
            val list = json.decodeFromString<List<DangerousElement>>(jsonStr)
            cachedDangers = list.toMutableList()
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading dangers", e)
            emptyList()
        }
    }

    private fun saveDangers(dangers: List<DangerousElement>) {
        cachedDangers = dangers.toMutableList()
        dangersPrefs.edit()
            .putString("dangers", json.encodeToString(dangers))
            .apply()
    }

    // ==================== Semantic Context ====================

    /**
     * Record semantic context - what a value means based on nearby elements
     */
    fun recordSemanticContext(
        valuePattern: String,
        nearbyIcons: List<String>,
        nearbyLabels: List<String>,
        inferredMeaning: String,
        inferredDeviceClass: String
    ) {
        val semantics = getAllSemantics().toMutableList()

        // Check for existing
        val existing = semantics.find { it.valuePattern == valuePattern }

        if (existing != null) {
            // Update with new context
            val updated = existing.copy(
                nearbyIcons = (existing.nearbyIcons + nearbyIcons).distinct().take(10),
                nearbyLabels = (existing.nearbyLabels + nearbyLabels).distinct().take(10),
                confidence = minOf(1.0f, existing.confidence + 0.1f)
            )
            semantics.remove(existing)
            semantics.add(updated)
        } else {
            semantics.add(SemanticContext(
                valuePattern = valuePattern,
                nearbyIcons = nearbyIcons.take(10),
                nearbyLabels = nearbyLabels.take(10),
                inferredMeaning = inferredMeaning,
                inferredDeviceClass = inferredDeviceClass,
                confidence = 0.6f
            ))
        }

        saveSemantics(semantics.takeLast(MAX_SEMANTICS))
        Log.d(TAG, "Recorded semantic context: $valuePattern -> $inferredMeaning")
    }

    /**
     * Find semantic meaning based on nearby context
     */
    fun findSemanticMeaning(valuePattern: String, nearbyText: List<String>): SemanticContext? {
        val semantics = getAllSemantics()

        // Look for exact pattern match with overlapping context
        return semantics.find { ctx ->
            ctx.valuePattern == valuePattern &&
            (ctx.nearbyLabels.any { label -> nearbyText.any { it.contains(label, ignoreCase = true) } } ||
             ctx.nearbyIcons.any { icon -> nearbyText.any { it.contains(icon, ignoreCase = true) } })
        }
    }

    private fun getAllSemantics(): List<SemanticContext> {
        cachedSemantics?.let { return it }

        return try {
            val jsonStr = semanticsPrefs.getString("semantics", null) ?: return emptyList()
            val list = json.decodeFromString<List<SemanticContext>>(jsonStr)
            cachedSemantics = list.toMutableList()
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading semantics", e)
            emptyList()
        }
    }

    private fun saveSemantics(semantics: List<SemanticContext>) {
        cachedSemantics = semantics.toMutableList()
        semanticsPrefs.edit()
            .putString("semantics", json.encodeToString(semantics))
            .apply()
    }

    // ==================== Action Learning ====================

    /**
     * Record an action pattern (button types, toggle controls, etc.)
     */
    fun recordActionPattern(
        appPackage: String,
        elementText: String?,
        resourceId: String?,
        actionType: ActionPatternType,
        suggestedName: String
    ) {
        val patterns = getAllPatterns().toMutableList()
        val patternKey = elementText ?: resourceId ?: return

        val existing = patterns.find {
            it.valuePattern == patternKey && it.patternType == PatternType.CUSTOM
        }

        if (existing != null) {
            val updated = existing.copy(
                confidence = minOf(1.0f, existing.confidence + 0.1f),
                occurrences = existing.occurrences + 1
            )
            patterns.remove(existing)
            patterns.add(updated)
        } else {
            patterns.add(LearnedPattern(
                valuePattern = patternKey,
                patternType = PatternType.CUSTOM,
                suggestedDeviceClass = actionType.name.lowercase(),
                suggestedUnit = suggestedName,
                appPackage = appPackage,
                confidence = 0.6f,
                occurrences = 1
            ))
        }

        savePatterns(patterns.takeLast(MAX_PATTERNS))
        Log.d(TAG, "Recorded action pattern: $patternKey -> $actionType ($suggestedName)")
    }

    /**
     * Detect action type from element text/resource-id
     */
    fun detectActionType(text: String?, resourceId: String?): ActionPatternSuggestion? {
        val lowerText = text?.lowercase() ?: ""
        val lowerId = resourceId?.lowercase() ?: ""
        val combined = "$lowerText $lowerId"

        return when {
            // Play/Pause controls
            combined.matches(Regex(""".*\b(play|pause|stop|resume)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.MEDIA_PLAYBACK,
                suggestedName = "Play/Pause",
                icon = "mdi:play-pause"
            )

            // Volume controls
            combined.matches(Regex(""".*\b(volume|mute|unmute|speaker)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.VOLUME_CONTROL,
                suggestedName = "Volume",
                icon = "mdi:volume-high"
            )

            // Navigation controls
            combined.matches(Regex(""".*\b(next|previous|skip|forward|backward)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.NAVIGATION,
                suggestedName = when {
                    combined.contains("next") -> "Next"
                    combined.contains("previous") || combined.contains("prev") -> "Previous"
                    combined.contains("skip") -> "Skip"
                    else -> "Navigate"
                },
                icon = if (combined.contains("next")) "mdi:skip-next" else "mdi:skip-previous"
            )

            // Toggle controls (on/off)
            combined.matches(Regex(""".*\b(switch|toggle|on|off|enable|disable)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.TOGGLE,
                suggestedName = "Toggle",
                icon = "mdi:toggle-switch"
            )

            // Climate controls
            combined.matches(Regex(""".*\b(heat|cool|ac|climate|hvac|temp.*up|temp.*down)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.CLIMATE_CONTROL,
                suggestedName = "Climate Control",
                icon = "mdi:thermostat"
            )

            // Lock/Unlock
            combined.matches(Regex(""".*\b(lock|unlock|secure)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.LOCK_CONTROL,
                suggestedName = if (combined.contains("unlock")) "Unlock" else "Lock",
                icon = if (combined.contains("unlock")) "mdi:lock-open" else "mdi:lock"
            )

            // Light controls
            combined.matches(Regex(""".*\b(light|lamp|brightness|dim|bright)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.LIGHT_CONTROL,
                suggestedName = "Light Control",
                icon = "mdi:lightbulb"
            )

            // Start/Stop
            combined.matches(Regex(""".*\b(start|begin|launch|stop|end|finish)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.START_STOP,
                suggestedName = when {
                    combined.contains("start") || combined.contains("begin") -> "Start"
                    combined.contains("stop") || combined.contains("end") -> "Stop"
                    else -> "Control"
                },
                icon = if (combined.contains("start")) "mdi:play" else "mdi:stop"
            )

            // Refresh/Reload
            combined.matches(Regex(""".*\b(refresh|reload|sync|update)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.REFRESH,
                suggestedName = "Refresh",
                icon = "mdi:refresh"
            )

            // Charging controls (EV)
            combined.matches(Regex(""".*\b(charge|charging|plug|unplug)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.CHARGING_CONTROL,
                suggestedName = "Charging Control",
                icon = "mdi:ev-station"
            )

            // Door controls
            combined.matches(Regex(""".*\b(door|trunk|hood|frunk|open|close)\b.*""")) -> ActionPatternSuggestion(
                actionType = ActionPatternType.DOOR_CONTROL,
                suggestedName = when {
                    combined.contains("trunk") -> "Trunk"
                    combined.contains("frunk") -> "Frunk"
                    combined.contains("hood") -> "Hood"
                    else -> "Door"
                },
                icon = "mdi:car-door"
            )

            else -> null
        }
    }

    /**
     * Get learned action name for an element
     */
    fun getLearnedActionName(appPackage: String, elementText: String?, resourceId: String?): String? {
        val patterns = getAllPatterns().filter { it.appPackage == appPackage }
        val patternKey = elementText ?: resourceId ?: return null

        return patterns.find { it.valuePattern == patternKey }?.suggestedUnit
    }

    // ==================== Manual Correction Support ====================

    /**
     * Record positive feedback for a specific element (user-taught path)
     */
    fun recordPositiveFeedback(
        packageName: String,
        screenId: String,
        elementId: String,
        actionType: String,
        reward: Float
    ) {
        val key = "positive_${packageName}_${screenId}_$elementId"
        val currentReward = patternsPrefs.getFloat(key, 0f)
        patternsPrefs.edit()
            .putFloat(key, currentReward + reward)
            .putString("action_type_$key", actionType)
            .apply()
        Log.d(TAG, "Recorded positive feedback: $elementId with reward $reward")
    }

    /**
     * Record a sensor pattern for future recognition
     */
    fun recordSensorPattern(packageName: String, pattern: String, sensorType: String) {
        val sensorPatterns = getSensorPatterns(packageName).toMutableMap()
        sensorPatterns[pattern.lowercase()] = sensorType
        patternsPrefs.edit()
            .putString("sensor_patterns_$packageName", json.encodeToString(sensorPatterns))
            .apply()
        Log.d(TAG, "Recorded sensor pattern: $pattern -> $sensorType")
    }

    /**
     * Get sensor type for a pattern (user-taught)
     */
    fun getSensorTypeForPattern(packageName: String, pattern: String): String? {
        val sensorPatterns = getSensorPatterns(packageName)
        return sensorPatterns[pattern.lowercase()]
    }

    private fun getSensorPatterns(packageName: String): Map<String, String> {
        return try {
            val jsonStr = patternsPrefs.getString("sensor_patterns_$packageName", null) ?: return emptyMap()
            json.decodeFromString<Map<String, String>>(jsonStr)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Record a custom action sequence for an element
     */
    fun recordActionSequence(
        packageName: String,
        screenId: String,
        elementId: String,
        actions: List<String>
    ) {
        val key = "action_seq_${packageName}_${screenId}_$elementId"
        patternsPrefs.edit()
            .putString(key, json.encodeToString(actions))
            .apply()
        Log.d(TAG, "Recorded action sequence for $elementId: ${actions.size} steps")
    }

    /**
     * Get custom action sequence for an element
     */
    fun getActionSequence(
        packageName: String,
        screenId: String,
        elementId: String
    ): List<com.visualmapper.companion.explorer.ActionStep>? {
        val key = "action_seq_${packageName}_${screenId}_$elementId"
        val jsonStr = patternsPrefs.getString(key, null) ?: return null
        return try {
            val actionJsons = json.decodeFromString<List<String>>(jsonStr)
            actionJsons.mapNotNull { parseActionStep(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing action sequence", e)
            null
        }
    }

    private fun parseActionStep(jsonStr: String): com.visualmapper.companion.explorer.ActionStep? {
        return try {
            val obj = org.json.JSONObject(jsonStr)
            com.visualmapper.companion.explorer.ActionStep(
                actionType = com.visualmapper.companion.explorer.ActionType.valueOf(obj.getString("type")),
                targetElementId = if (obj.has("target")) obj.getString("target") else null,
                text = if (obj.has("text")) obj.getString("text") else null,
                delayMs = obj.optLong("delay", 0)
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Record a user-taught "golden path" for navigation
     */
    fun recordGoldenPath(packageName: String, pathName: String, steps: List<String>) {
        val paths = getGoldenPathsInternal(packageName).toMutableList()
        paths.add(GoldenPathData(pathName, steps, System.currentTimeMillis()))

        // Keep only last 20 paths per app
        val trimmed = paths.takeLast(20)
        patternsPrefs.edit()
            .putString("golden_paths_$packageName", json.encodeToString(trimmed))
            .apply()
        Log.d(TAG, "Recorded golden path: $pathName with ${steps.size} steps")
    }

    /**
     * Get golden paths for an app
     */
    fun getGoldenPaths(packageName: String): List<com.visualmapper.companion.explorer.GoldenPath> {
        return getGoldenPathsInternal(packageName).map { data ->
            com.visualmapper.companion.explorer.GoldenPath(
                name = data.name,
                steps = data.steps.mapNotNull { parsePathStep(it) },
                createdAt = data.createdAt
            )
        }
    }

    private fun getGoldenPathsInternal(packageName: String): List<GoldenPathData> {
        return try {
            val jsonStr = patternsPrefs.getString("golden_paths_$packageName", null) ?: return emptyList()
            json.decodeFromString<List<GoldenPathData>>(jsonStr)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePathStep(jsonStr: String): com.visualmapper.companion.explorer.PathStep? {
        return try {
            val obj = org.json.JSONObject(jsonStr)
            com.visualmapper.companion.explorer.PathStep(
                screenId = obj.getString("screenId"),
                elementId = obj.getString("elementId"),
                elementText = if (obj.has("text")) obj.getString("text") else null,
                elementResourceId = null,
                resultScreenId = if (obj.has("resultScreen")) obj.getString("resultScreen") else null,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    @Serializable
    private data class GoldenPathData(
        val name: String,
        val steps: List<String>,
        val createdAt: Long
    )

    // ==================== Statistics ====================

    fun getStats(): LearningStats {
        return LearningStats(
            patternCount = getAllPatterns().size,
            dangerCount = getAllDangers().size,
            semanticCount = getAllSemantics().size
        )
    }

    fun clearAll() {
        cachedPatterns = mutableListOf()
        cachedDangers = mutableListOf()
        cachedSemantics = mutableListOf()
        patternsPrefs.edit().clear().apply()
        dangersPrefs.edit().clear().apply()
        semanticsPrefs.edit().clear().apply()
        Log.d(TAG, "Cleared all learning data")
    }

    // ==================== Strategy Learning ====================

    /**
     * Record the best performing exploration strategy for an app
     */
    fun recordBestStrategy(packageName: String, strategy: com.visualmapper.companion.explorer.ExplorationStrategy) {
        patternsPrefs.edit()
            .putString("strategy_$packageName", strategy.name)
            .putLong("strategy_time_$packageName", System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Recorded best strategy for $packageName: ${strategy.name}")
    }

    /**
     * Get the previously learned best strategy for an app
     */
    fun getBestStrategy(packageName: String): com.visualmapper.companion.explorer.ExplorationStrategy? {
        val strategyName = patternsPrefs.getString("strategy_$packageName", null) ?: return null
        return try {
            com.visualmapper.companion.explorer.ExplorationStrategy.valueOf(strategyName)
        } catch (e: Exception) {
            Log.w(TAG, "Unknown strategy: $strategyName")
            null
        }
    }

    /**
     * Get all learned strategies for stats display
     */
    fun getAllLearnedStrategies(): Map<String, String> {
        val strategies = mutableMapOf<String, String>()
        patternsPrefs.all.forEach { (key, value) ->
            if (key.startsWith("strategy_") && !key.contains("_time_") && value is String) {
                val packageName = key.removePrefix("strategy_")
                strategies[packageName] = value
            }
        }
        return strategies
    }
}

// ==================== Data Classes ====================

@Serializable
enum class PatternType {
    PERCENTAGE,
    TEMPERATURE,
    DISTANCE,
    SPEED,
    CURRENCY,
    TIME_DURATION,
    DATA_SIZE,
    SIGNAL_BARS,
    POWER_ENERGY,
    VOLTAGE,
    CUSTOM
}

@Serializable
data class LearnedPattern(
    val valuePattern: String,
    val patternType: PatternType,
    val regex: String? = null,
    val suggestedDeviceClass: String,
    val suggestedUnit: String? = null,
    val appPackage: String? = null, // null = global pattern
    val confidence: Float = 0.5f,
    val occurrences: Int = 1
)

data class PatternSuggestion(
    val deviceClass: String,
    val unit: String?,
    val confidence: Float,
    val patternType: PatternType
)

@Serializable
enum class DangerType {
    LOGOUT,
    DELETE,
    RESET,
    CLOSE_APP,
    PURCHASE,
    EXTERNAL_LINK,
    PERMISSION_REVOKE
}

@Serializable
data class DangerousElement(
    val appPackage: String,
    val screenId: String,
    val elementPattern: String,
    val dangerType: DangerType,
    val consequences: String,
    val recordedAt: Long = System.currentTimeMillis()
)

@Serializable
data class SemanticContext(
    val valuePattern: String,
    val nearbyIcons: List<String> = emptyList(),
    val nearbyLabels: List<String> = emptyList(),
    val inferredMeaning: String,
    val inferredDeviceClass: String,
    val confidence: Float = 0.5f
)

data class LearningStats(
    val patternCount: Int,
    val dangerCount: Int,
    val semanticCount: Int
)

/**
 * Types of action patterns for button/control detection
 */
@Serializable
enum class ActionPatternType {
    MEDIA_PLAYBACK,      // Play, Pause, Stop
    VOLUME_CONTROL,      // Volume up/down, mute
    NAVIGATION,          // Next, Previous, Skip
    TOGGLE,              // On/Off switches
    CLIMATE_CONTROL,     // Heat, Cool, AC
    LOCK_CONTROL,        // Lock, Unlock
    LIGHT_CONTROL,       // Light on/off, brightness
    START_STOP,          // Start, Stop actions
    REFRESH,             // Refresh, Reload, Sync
    CHARGING_CONTROL,    // EV charging start/stop
    DOOR_CONTROL,        // Door, Trunk, Hood
    CUSTOM               // User-defined actions
}

/**
 * Suggested action based on pattern detection
 */
data class ActionPatternSuggestion(
    val actionType: ActionPatternType,
    val suggestedName: String,
    val icon: String? = null
)
