package com.metrolauncher.util

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.metrolauncher.model.Tile

/**
 * Start screen persistence: we serialize the list of tiles to JSON in SharedPreferences.
 * Efficient enough for hundreds of tiles, near-zero overhead.
 */
class TileStorage(ctx: Context) {

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): MutableList<Tile> {
        val json = prefs.getString(KEY_TILES, null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<Tile>>() {}.type
        return runCatching { gson.fromJson<MutableList<Tile>>(json, type) }
            .getOrNull() ?: mutableListOf()
    }

    fun save(tiles: List<Tile>) {
        prefs.edit { putString(KEY_TILES, gson.toJson(tiles)) }
    }

    fun clear() = prefs.edit { remove(KEY_TILES) }

    companion object {
        private const val PREFS = "metro_launcher_tiles"
        private const val KEY_TILES = "tiles_v1"
    }
}
