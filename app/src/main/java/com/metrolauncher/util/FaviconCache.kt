package com.metrolauncher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading and persisting favicons for WEB_LINK tiles.
 *
 * Favicons are saved in filesDir/favicons/{tileId}.png so they survive 
 * reboots without requiring a new network request.
 *
 * Source: Google Favicon API — works for virtually any domain, no API key 
 * required, returns 128x128 PNG.
 */
object FaviconCache {

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "favicons").also { it.mkdirs() }

    private fun file(ctx: Context, tileId: String): File =
        File(dir(ctx), "$tileId.png")

    /** Loads the favicon saved on disk. Null if it doesn't exist. */
    fun load(ctx: Context, tileId: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(file(ctx, tileId).absolutePath) }.getOrNull()

    /** Saves a bitmap to disk. Overwrites if already present. */
    fun save(ctx: Context, tileId: String, bmp: Bitmap) {
        runCatching {
            file(ctx, tileId).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Deletes the favicon of a tile (when removed from Start). */
    fun delete(ctx: Context, tileId: String) {
        runCatching { file(ctx, tileId).delete() }
    }

    /** True if the favicon is already in disk cache. */
    fun exists(ctx: Context, tileId: String): Boolean = file(ctx, tileId).exists()

    /**
     * Downloads the favicon for the given domain and saves it for [tileId].
     * To be called on Dispatchers.IO.
     *
     * Tries in order:
     *  1. /favicon.ico directly from the site (high quality if available)
     *  2. Google Favicon API (reliable fallback)
     *
     * Returns the Bitmap if download was successful, null otherwise.
     */
    fun fetchAndSave(ctx: Context, tileId: String, url: String): Bitmap? {
        val domain = runCatching {
            val host = Uri.parse(url).host ?: return null
            // Removes www. for a cleaner query
            if (host.startsWith("www.")) host.substring(4) else host
        }.getOrNull() ?: return null

        // First try Google Favicon API — the most reliable
        val googleUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
        val bmp = download(googleUrl)
            ?: download("https://$domain/favicon.ico")
            ?: return null

        save(ctx, tileId, bmp)
        return bmp
    }

    private fun download(urlString: String): Bitmap? = runCatching {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connect()
        if (conn.responseCode != 200) return@runCatching null
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
