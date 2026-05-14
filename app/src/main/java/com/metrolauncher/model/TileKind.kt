package com.metrolauncher.model

/**
 * Rendering type of a tile.
 *   APP       — standard tile (icon + label + live notifications/media)
 *   CALENDAR  — shows current date and upcoming events (even without opening app)
 *   CLOCK     — shows current time and next alarm (via AlarmManager)
 *   WEATHER   — temp + location + animated background for weather condition (Open-Meteo)
 *   GALLERY   — slideshow of photos from a folder chosen by the user
 *
 * User promotes an APP tile to CALENDAR/CLOCK/WEATHER/GALLERY from long-press menu,
 * only if the underlying package is one of the known apps (see Prefs / KnownPackages).
 */
enum class TileKind { APP, CALENDAR, CLOCK, WEATHER, GALLERY, FOLDER, WEB_LINK }
