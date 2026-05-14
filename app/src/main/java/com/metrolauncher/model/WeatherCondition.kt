package com.metrolauncher.model

/**
 * Simplified weather condition used for weather tile rendering.
 * Derived from WMO weather codes returned by Open-Meteo, mapped with a 
 * granularity that makes sense visually (no need to distinguish "light fog" 
 * from "heavy fog" at the animated background level).
 */
enum class WeatherCondition {
    CLEAR,         // clear sky
    PARTLY_CLOUDY, // partly cloudy
    CLOUDY,        // overcast
    FOG,           // fog
    RAIN,          // rain
    THUNDERSTORM,  // thunderstorm
    SNOW,          // snow
    UNKNOWN;       // not available (first startup, network error)

    companion object {
        /**
         * Maps a WMO weather code (https://open-meteo.com/en/docs) to the condition.
         * 0       → clear
         * 1-2     → partly cloudy
         * 3       → overcast
         * 45, 48  → fog
         * 51-67   → rain (light/moderate/heavy, all grouped)
         * 71-77   → snow
         * 80-82   → showers → rain
         * 85-86   → snow showers → snow
         * 95-99   → thunderstorm
         */
        fun fromWmoCode(code: Int): WeatherCondition = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDERSTORM
            else -> UNKNOWN
        }
    }
}
