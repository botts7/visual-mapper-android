package com.visualmapper.companion.explorer.learning

/**
 * Comprehensive Home Assistant device classes, units, and state classes.
 * Bundled with the app for standalone operation.
 * Can be updated from server when connected.
 */
object BundledDeviceClasses {

    // All HA sensor device classes (from HA documentation)
    val SENSOR_DEVICE_CLASSES = listOf(
        "apparent_power",
        "aqi",
        "atmospheric_pressure",
        "battery",
        "carbon_dioxide",
        "carbon_monoxide",
        "current",
        "data_rate",
        "data_size",
        "date",
        "distance",
        "duration",
        "energy",
        "energy_storage",
        "enum",
        "frequency",
        "gas",
        "humidity",
        "illuminance",
        "irradiance",
        "moisture",
        "monetary",
        "nitrogen_dioxide",
        "nitrogen_monoxide",
        "nitrous_oxide",
        "ozone",
        "ph",
        "pm1",
        "pm10",
        "pm25",
        "power",
        "power_factor",
        "precipitation",
        "precipitation_intensity",
        "pressure",
        "reactive_power",
        "signal_strength",
        "sound_pressure",
        "speed",
        "sulphur_dioxide",
        "temperature",
        "timestamp",
        "volatile_organic_compounds",
        "volatile_organic_compounds_parts",
        "voltage",
        "volume",
        "volume_flow_rate",
        "volume_storage",
        "water",
        "weight",
        "wind_speed"
    )

    // All HA binary sensor device classes
    val BINARY_SENSOR_DEVICE_CLASSES = listOf(
        "battery",
        "battery_charging",
        "carbon_monoxide",
        "cold",
        "connectivity",
        "door",
        "garage_door",
        "gas",
        "heat",
        "light",
        "lock",
        "moisture",
        "motion",
        "moving",
        "occupancy",
        "opening",
        "plug",
        "power",
        "presence",
        "problem",
        "running",
        "safety",
        "smoke",
        "sound",
        "tamper",
        "update",
        "vibration",
        "window"
    )

    // State classes for sensors
    val STATE_CLASSES = listOf(
        "measurement",      // Current reading that changes
        "total",           // Total that only increases (resets allowed)
        "total_increasing" // Total that only ever increases
    )

    // Units organized by device class
    val UNITS_BY_DEVICE_CLASS = mapOf(
        "temperature" to listOf("°C", "°F", "K"),
        "battery" to listOf("%"),
        "humidity" to listOf("%"),
        "moisture" to listOf("%"),
        "power" to listOf("W", "kW", "MW", "GW"),
        "apparent_power" to listOf("VA", "kVA"),
        "reactive_power" to listOf("var", "kvar"),
        "energy" to listOf("Wh", "kWh", "MWh", "GWh", "J", "kJ", "MJ", "GJ"),
        "energy_storage" to listOf("Wh", "kWh", "MWh"),
        "voltage" to listOf("V", "mV", "kV"),
        "current" to listOf("A", "mA"),
        "power_factor" to listOf("%"),
        "frequency" to listOf("Hz", "kHz", "MHz", "GHz"),
        "pressure" to listOf("Pa", "hPa", "kPa", "bar", "mbar", "psi", "inHg", "mmHg"),
        "atmospheric_pressure" to listOf("Pa", "hPa", "kPa", "bar", "mbar", "inHg", "mmHg"),
        "distance" to listOf("m", "km", "mi", "ft", "in", "cm", "mm", "yd"),
        "speed" to listOf("m/s", "km/h", "mph", "kn", "ft/s"),
        "wind_speed" to listOf("m/s", "km/h", "mph", "kn", "ft/s", "Beaufort"),
        "volume" to listOf("L", "mL", "gal", "fl. oz.", "m³", "ft³", "CCF"),
        "volume_storage" to listOf("L", "mL", "gal", "m³", "ft³"),
        "volume_flow_rate" to listOf("L/min", "L/h", "m³/h", "gal/min", "ft³/min"),
        "weight" to listOf("kg", "g", "mg", "lb", "oz", "st"),
        "illuminance" to listOf("lx", "lm"),
        "irradiance" to listOf("W/m²", "BTU/(h⋅ft²)"),
        "precipitation" to listOf("mm", "in", "cm"),
        "precipitation_intensity" to listOf("mm/h", "in/h", "mm/d", "in/d"),
        "signal_strength" to listOf("dB", "dBm"),
        "sound_pressure" to listOf("dB", "dBA"),
        "data_size" to listOf("B", "KB", "MB", "GB", "TB", "PB", "KiB", "MiB", "GiB", "TiB"),
        "data_rate" to listOf("bit/s", "kbit/s", "Mbit/s", "Gbit/s", "B/s", "KB/s", "MB/s", "GB/s"),
        "duration" to listOf("s", "ms", "min", "h", "d"),
        "carbon_dioxide" to listOf("ppm"),
        "carbon_monoxide" to listOf("ppm"),
        "nitrogen_dioxide" to listOf("µg/m³"),
        "nitrogen_monoxide" to listOf("µg/m³"),
        "nitrous_oxide" to listOf("µg/m³"),
        "ozone" to listOf("µg/m³"),
        "pm1" to listOf("µg/m³"),
        "pm10" to listOf("µg/m³"),
        "pm25" to listOf("µg/m³"),
        "sulphur_dioxide" to listOf("µg/m³"),
        "volatile_organic_compounds" to listOf("µg/m³"),
        "volatile_organic_compounds_parts" to listOf("ppb"),
        "aqi" to listOf(""),
        "ph" to listOf(""),
        "gas" to listOf("m³", "ft³", "CCF"),
        "water" to listOf("L", "gal", "m³", "ft³"),
        "monetary" to listOf("$", "€", "£", "¥", "₹", "₽")
    )

    // Common icons by device class (MDI icon names)
    val ICONS_BY_DEVICE_CLASS = mapOf(
        "temperature" to "mdi:thermometer",
        "battery" to "mdi:battery",
        "humidity" to "mdi:water-percent",
        "moisture" to "mdi:water",
        "power" to "mdi:flash",
        "energy" to "mdi:lightning-bolt",
        "voltage" to "mdi:sine-wave",
        "current" to "mdi:current-ac",
        "pressure" to "mdi:gauge",
        "distance" to "mdi:ruler",
        "speed" to "mdi:speedometer",
        "wind_speed" to "mdi:weather-windy",
        "volume" to "mdi:cup-water",
        "weight" to "mdi:weight",
        "illuminance" to "mdi:brightness-5",
        "signal_strength" to "mdi:signal",
        "carbon_dioxide" to "mdi:molecule-co2",
        "carbon_monoxide" to "mdi:molecule-co",
        "aqi" to "mdi:air-filter",
        "gas" to "mdi:gas-cylinder",
        "water" to "mdi:water",
        "monetary" to "mdi:currency-usd",
        "duration" to "mdi:timer",
        "frequency" to "mdi:sine-wave",
        "connectivity" to "mdi:wifi",
        "motion" to "mdi:motion-sensor",
        "occupancy" to "mdi:account-multiple",
        "door" to "mdi:door",
        "window" to "mdi:window-closed",
        "lock" to "mdi:lock",
        "plug" to "mdi:power-plug",
        "light" to "mdi:lightbulb"
    )

    // Pattern recognition for auto-detecting device class
    val VALUE_PATTERNS = mapOf(
        // Percentage patterns
        Regex("""(\d+(?:\.\d+)?)\s*%""") to PatternMatch("battery", "%", "Percentage value"),
        // Temperature patterns
        Regex("""(-?\d+(?:\.\d+)?)\s*°?[CF]""") to PatternMatch("temperature", null, "Temperature"),
        Regex("""(-?\d+(?:\.\d+)?)\s*degrees?""") to PatternMatch("temperature", null, "Temperature"),
        // Distance patterns
        Regex("""(\d+(?:,\d{3})*(?:\.\d+)?)\s*(?:km|mi|miles?)""") to PatternMatch("distance", null, "Distance"),
        Regex("""(\d+(?:,\d{3})*(?:\.\d+)?)\s*(?:m|ft|feet)(?:\s|$)""") to PatternMatch("distance", null, "Distance"),
        // Speed patterns
        Regex("""(\d+(?:\.\d+)?)\s*(?:km/h|mph|m/s|kph)""") to PatternMatch("speed", null, "Speed"),
        // Power/Energy patterns
        Regex("""(\d+(?:\.\d+)?)\s*(?:kW|MW|W)(?:h)?""") to PatternMatch("power", null, "Power"),
        Regex("""(\d+(?:\.\d+)?)\s*(?:kWh|MWh|Wh)""") to PatternMatch("energy", null, "Energy"),
        // Voltage/Current
        Regex("""(\d+(?:\.\d+)?)\s*V(?:olts?)?""") to PatternMatch("voltage", "V", "Voltage"),
        Regex("""(\d+(?:\.\d+)?)\s*A(?:mps?)?""") to PatternMatch("current", "A", "Current"),
        // Pressure
        Regex("""(\d+(?:\.\d+)?)\s*(?:psi|bar|kPa|hPa)""") to PatternMatch("pressure", null, "Pressure"),
        // Data size
        Regex("""(\d+(?:\.\d+)?)\s*(?:GB|MB|KB|TB)""") to PatternMatch("data_size", null, "Data Size"),
        // Duration
        Regex("""(\d+)\s*(?:h|hr|hours?)\s*(\d+)?\s*(?:m|min)?""") to PatternMatch("duration", null, "Duration"),
        Regex("""(\d+)\s*(?:min|minutes?)""") to PatternMatch("duration", "min", "Duration"),
        // Currency
        Regex("""[$€£¥₹]\s*(\d+(?:,\d{3})*(?:\.\d{2})?)""") to PatternMatch("monetary", null, "Currency"),
        Regex("""(\d+(?:,\d{3})*(?:\.\d{2})?)\s*[$€£¥₹]""") to PatternMatch("monetary", null, "Currency")
    )

    data class PatternMatch(
        val deviceClass: String,
        val unit: String?,
        val description: String
    )

    /**
     * Detect device class from a raw text value
     */
    fun detectDeviceClass(rawValue: String): PatternMatch? {
        for ((pattern, match) in VALUE_PATTERNS) {
            if (pattern.containsMatchIn(rawValue)) {
                return match
            }
        }
        return null
    }

    /**
     * Get suggested units for a device class
     */
    fun getUnitsForDeviceClass(deviceClass: String): List<String> {
        return UNITS_BY_DEVICE_CLASS[deviceClass] ?: emptyList()
    }

    /**
     * Get suggested icon for a device class
     */
    fun getIconForDeviceClass(deviceClass: String): String? {
        return ICONS_BY_DEVICE_CLASS[deviceClass]
    }

    /**
     * Extract numeric value from text
     */
    fun extractNumericValue(text: String): Double? {
        val cleanText = text.replace(",", "")
        val match = Regex("""(-?\d+(?:\.\d+)?)""").find(cleanText)
        return match?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Format device class for display (e.g., "carbon_dioxide" -> "Carbon Dioxide")
     */
    fun formatDeviceClassName(deviceClass: String): String {
        return deviceClass.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }
}
