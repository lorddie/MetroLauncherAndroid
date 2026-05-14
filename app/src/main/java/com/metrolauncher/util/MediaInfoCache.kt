package com.metrolauncher.util

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory cache of the "current media" state for each app playing 
 * something with a MediaSession (Spotify, YouTube Music, VLC, podcast, etc.).
 *
 * Populated by NotificationListener via MediaSessionManager.getActiveSessions().
 * Consumed by TileGridLayout/TileView to draw media tiles.
 *
 * Singleton because NotificationListener (Service) and MainActivity live in 
 * the same process: no cross-process Bitmap parceling, all by reference.
 */
object MediaInfoCache {

    data class MediaInfo(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val albumArt: Bitmap?,       // può essere null
        val positionMs: Long,        // "official" position received from system
        val durationMs: Long,        // 0 or negative if unknown/livestream
        val isPlaying: Boolean,
        /** SystemClock.elapsedRealtime() at the moment positionMs was read. 
         *  Used to extrapolate current position between updates. */
        val positionCapturedAt: Long
    ) {
        /** Current extrapolated position: if playing, advances with real elapsed time. */
        fun currentPositionMs(): Long {
            if (!isPlaying) return positionMs
            val elapsed = SystemClock.elapsedRealtime() - positionCapturedAt
            val p = positionMs + elapsed
            return if (durationMs > 0) p.coerceAtMost(durationMs) else p
        }
    }

    private val map = ConcurrentHashMap<String, MediaInfo>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun interface Listener {
        /** Called when media info for `packageName` has changed or been removed. */
        fun onMediaChanged(packageName: String)
    }

    fun get(pkg: String): MediaInfo? = map[pkg]

    /** Snapshot of apps with active media (to decide if the tick timer should run). */
    fun activePackages(): Set<String> = map.keys.toSet()

    fun anyPlaying(): Boolean = map.values.any { it.isPlaying }

    fun update(info: MediaInfo) {
        map[info.packageName] = info
        listeners.forEach { it.onMediaChanged(info.packageName) }
    }

    fun remove(pkg: String) {
        if (map.remove(pkg) != null) {
            listeners.forEach { it.onMediaChanged(pkg) }
        }
    }

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
}
