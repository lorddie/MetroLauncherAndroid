package com.metrolauncher.util

import android.content.Context
import androidx.core.content.edit

/**
 * Centralized helper for all launcher SharedPreferences.
 * All defaults are here, so activities don't need to duplicate them.
 */
object Prefs {
    private const val FILE = "metro_launcher_prefs"

    // Legacy keys (backward compatibility)
    const val KEY_TRANSPARENT_TILES = "transparent_tiles_default"
    const val KEY_SHOW_WALLPAPER = "show_wallpaper"
    const val KEY_DARK_THEME = "dark_theme"
    const val KEY_FIRST_RUN = "first_run"
    const val KEY_TILE_ANIMATION = "tile_animation"

    // New keys
    const val KEY_GRID_COLUMNS = "grid_columns"           // Int: 4 or 6
    const val KEY_LOCK_ROTATION = "lock_rotation"         // Bool
    const val KEY_TILE_FONT_SCALE = "tile_font_scale"     // String: "small" | "normal" | "large"
    const val KEY_DRAWER_FONT_SCALE = "drawer_font_scale" // String: "small" | "normal" | "large"
    const val KEY_WALLPAPER_URI = "wallpaper_uri"         // String (URI) or null -> use system wallpaper

    // Global tile transparency, 0..100 (100 = opaque)
    const val KEY_TILE_OPACITY = "tile_opacity"
    const val DEFAULT_TILE_OPACITY = 100

    // Use a single color for all tiles?
    const val KEY_USE_GLOBAL_COLOR = "use_global_color"
    // Index in ColorUtils.METRO_COLORS when KEY_USE_GLOBAL_COLOR is true.
    // Special value -1 = use KEY_GLOBAL_COLOR_CUSTOM (free color chosen by user).
    const val KEY_GLOBAL_COLOR_INDEX = "global_color_index"
    const val DEFAULT_GLOBAL_COLOR_INDEX = 0  // blue
    const val CUSTOM_COLOR_SENTINEL = -1
    // ARGB color chosen from "Custom..." dialog. Used when KEY_GLOBAL_COLOR_INDEX 
    // is CUSTOM_COLOR_SENTINEL. Default = Metro blue (#FF0078D7) to not start from black.
    const val KEY_GLOBAL_COLOR_CUSTOM = "global_color_custom"
    const val DEFAULT_GLOBAL_COLOR_CUSTOM = 0xFF0078D7.toInt()

    // Monochrome style: icons rendered as a white silhouette instead of the original icon
    const val KEY_MONOCHROME_ICONS = "monochrome_icons"

    // Tile text color: "white" or "black". Applied uniformly to ALL 
    // tiles (labels, badges, live notifications, calendar) ignoring the old 
    // "auto" behavior that decided white/black based on accent color brightness. 
    // Motivation: consistent appearance is needed — the calendar live tile 
    // didn't follow the old logic because it reads `labelPaint.color`.
    const val KEY_TEXT_COLOR_MODE = "text_color_mode"
    const val DEFAULT_TEXT_COLOR_MODE = "white"

    // Background mode
    //   "normal"       -> full-screen wallpaper visible behind tiles (default)
    //   "black"        -> no wallpaper, black background
    //   "tiles_only"   -> black background, wallpaper visible ONLY through tiles
    const val KEY_BACKGROUND_MODE = "background_mode"
    const val DEFAULT_BACKGROUND_MODE = "normal"

    // Defaults
    const val DEFAULT_GRID_COLUMNS = 4
    const val DEFAULT_FONT_SCALE = "normal"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun bool(ctx: Context, key: String, default: Boolean = false): Boolean =
        sp(ctx).getBoolean(key, default)

    fun setBool(ctx: Context, key: String, value: Boolean) =
        sp(ctx).edit { putBoolean(key, value) }

    fun int(ctx: Context, key: String, default: Int): Int =
        sp(ctx).getInt(key, default)

    fun setInt(ctx: Context, key: String, value: Int) =
        sp(ctx).edit { putInt(key, value) }

    fun string(ctx: Context, key: String, default: String? = null): String? =
        sp(ctx).getString(key, default)

    fun setString(ctx: Context, key: String, value: String?) =
        sp(ctx).edit { if (value == null) remove(key) else putString(key, value) }

    /** Typed helper: returns the number of columns to use (4 or 6). */
    fun gridColumns(ctx: Context): Int {
        val stored = int(ctx, KEY_GRID_COLUMNS, DEFAULT_GRID_COLUMNS)
        return if (stored == 6) 6 else 4
    }

    /** Converts a string scale into a float multiplier. */
    fun fontScaleFactor(scale: String?): Float = when (scale) {
        "small"  -> 0.85f
        "large"  -> 1.20f
        else     -> 1.0f
    }
}
