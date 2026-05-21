package com.metrolauncher.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.metrolauncher.util.MediaInfoCache
import kotlin.math.max
import kotlin.math.min

/**
 * Two responsibilities:
 *
 * 1. Broadcasts "textual" notifications (title/body/badge) to MainActivity for 
 *    standard live tiles.
 *
 * 2. Subscribes via MediaSessionManager to all active MediaSessions 
 *    (Spotify, YouTube Music, VLC, podcast players, etc.), and feeds MediaInfoCache 
 *    with structured metadata: song title, artist, album art (Bitmap), 
 *    position, duration, play/pause state. Media app tiles automatically 
 *    transform into "mini players" with the album cover as background.
 */
class NotificationListener : NotificationListenerService() {

    interface Listener {
        fun onNotificationUpdate(pkg: String, title: String?, body: String?, count: Int, removed: Boolean, messages: List<String>)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaSessionManager: MediaSessionManager? = null
    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            rebindControllers(sessions ?: emptyList())
        }
    /** package -> controller. We keep only one controller per package (the first one). */
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()

    // ---------- Lifecycle --------------------------------------------------

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MetroNotif", "NotificationListener connected")
        instance = this
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        mediaSessionManager = msm
        val component = ComponentName(this, NotificationListener::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(activeSessionsListener, component, mainHandler)
            rebindControllers(msm.getActiveSessions(component))
        }

        // Upon connection, republish all current notifications to "wake up" the tiles
        try {
            activeNotifications?.map { it.packageName }?.distinct()?.forEach { pkg ->
                republishAggregateFor(pkg)
            }
        } catch (e: Exception) {
            Log.e("MetroNotif", "Error during initial republish", e)
        }
    }

    override fun onListenerDisconnected() {
        Log.d("MetroNotif", "NotificationListener disconnected")
        if (instance === this) instance = null
        runCatching {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        }
        // Clears all callbacks
        for ((controller, cb) in callbacks) {
            runCatching { controller.unregisterCallback(cb) }
        }
        callbacks.clear()
        for (pkg in controllers.keys.toList()) MediaInfoCache.remove(pkg)
        controllers.clear()
        super.onListenerDisconnected()
    }

    // ---------- Textual notifications (standard live tiles) -------------------

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val flags = sbn.notification.flags
        Log.d("MetroNotif", "POSTED: pkg=${sbn.packageName}, id=${sbn.id}, summary=${(flags and Notification.FLAG_GROUP_SUMMARY) != 0}, ongoing=${(flags and Notification.FLAG_ONGOING_EVENT) != 0}")
        // Republishes the aggregate state of the entire package, not just this notification:
        // for messaging apps we want the sum of unread counts and the most recent message.
        republishAggregateFor(sbn.packageName)
    }

    /** MessagingStyle extraction return: only the fields we need. */
    private data class MessagingInfo(
        /** Last sender (for backward compatibility). */
        val lastSender: String?,
        /** Last text (for backward compatibility). */
        val lastText: String?,
        /** Total unread count. */
        val unreadCount: Int,
        /** Full list of messages in chronological order (ascending), up to 10.
         *  Each string has the format "Sender: text" (or just text if sender is null). */
        val messages: List<String>
    )

    /**
     * Searches notification extras for fields populated by NotificationCompat.MessagingStyle
     * (used by WhatsApp, Telegram, Signal, Messenger, Google Messages, etc.).
     *
     * Official keys:
     *   "android.messages"            -> Parcelable[] with Bundle for each message
     *   each Bundle contains: "text" (CharSequence), "time" (Long), "sender" (CharSequence)
     */
    private fun extractMessagingStyle(sbn: StatusBarNotification): MessagingInfo? {
        val extras = sbn.notification.extras ?: return null
        val messagesArr = runCatching {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull() ?: return null
        if (messagesArr.isEmpty()) return null

        data class Msg(val text: String, val sender: String?, val time: Long)
        val parsed = mutableListOf<Msg>()
        for (item in messagesArr) {
            val b = item as? android.os.Bundle ?: continue
            val text = b.getCharSequence("text")?.toString() ?: continue
            if (text.isBlank()) continue
            val sender = b.getCharSequence("sender")?.toString()
                ?: b.getBundle("sender_person")?.getCharSequence("name")?.toString()
            val time = b.getLong("time", 0L)
            parsed.add(Msg(text, sender, time))
        }
        if (parsed.isEmpty()) return null
        parsed.sortBy { it.time }
        val last = parsed.last()
        val published = sbn.notification.number
        val unread = if (published > 0) published else parsed.size
        val formatted = parsed.takeLast(10).map { m ->
            if (!m.sender.isNullOrBlank()) "${m.sender}: ${m.text}" else m.text
        }
        return MessagingInfo(last.sender, last.text, unread, formatted)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val flags = sbn.notification.flags
        Log.d("MetroNotif", "REMOVED EVENT: pkg=${sbn.packageName}, id=${sbn.id}, summary=${(flags and Notification.FLAG_GROUP_SUMMARY) != 0}")
        
        val pkg = sbn.packageName
        val key = sbn.key
        
        // First rapid check (350ms)
        mainHandler.postDelayed({
            Log.d("MetroNotif", "First delayed scan for $pkg after removal")
            republishAggregateFor(pkg, removingKey = key)
        }, 350L)

        // Second safety check (1200ms) for "stubborn" cases where Android 
        // keeps the summary alive for an extra second after the user has cleared everything.
        mainHandler.postDelayed({
            Log.d("MetroNotif", "Safety double-check scan for $pkg")
            republishAggregateFor(pkg)
        }, 1200L)
    }

    /**
     * Patterns for "sensitive" packages — banking, email, password managers. For these 
     * we show only the notification count, not title/body/messages, for privacy 
     * reasons.
     *
     * This is a heuristic, not an exhaustive blacklist. It captures common keywords 
     * in the package name (banking, bank, finance, email, mail, outlook, gmail, etc.).
     */
    private fun isSensitivePackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return SENSITIVE_KEYWORDS.any { p.contains(it) } ||
               SENSITIVE_PKG_EXACT.any { p == it }
    }

    private fun republishAggregateFor(pkg: String, removingKey: String? = null) {
        val allRaw = try { activeNotifications } catch (e: SecurityException) { null }
        
        // Filter "noise" notifications
        val active = allRaw?.filter {
            it.packageName == pkg && 
            it.key != removingKey &&
            (it.notification.flags and Notification.FLAG_ONGOING_EVENT) == 0 &&
            (it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) == 0 &&
            (it.notification.flags and Notification.FLAG_NO_CLEAR) == 0
        } ?: emptyList()

        Log.d("MetroNotif", "REPUBLISH: $pkg -> activeCount=${active.size} (rawCount=${allRaw?.filter { it.packageName == pkg }?.size ?: 0})")

        if (active.isEmpty()) {
            Log.d("MetroNotif", "REPUBLISH: $pkg is truly empty, clearing.")
            broadcast(pkg, null, null, 0, removed = true, messages = emptyList())
            return
        }

        var summaryUnread = 0
        var individualUnread = 0
        val messagePairs = mutableListOf<Pair<Long, String>>() // time to text
        var latestWhen = Long.MIN_VALUE
        var latestText: String? = null
        var latestSender: String? = null

        for (sbn in active) {
            val isSummary = (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            val msg = extractMessagingStyle(sbn)
            val extras = sbn.notification.extras
            
            // Standard text
            val stdTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val stdText = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

            Log.d("MetroNotif", "  - SCANNED: key=${sbn.key}, summary=$isSummary, num=${sbn.notification.number}, text=${stdText?.take(15)}...")

            // Guard: if it has no text, number, nor messages, ignore it (probable phantom)
            if (stdTitle.isNullOrBlank() && stdText.isNullOrBlank() && sbn.notification.number <= 0 && msg == null) {
                Log.d("MetroNotif", "    -> Ignored: No content found")
                continue
            }

            if (msg != null) {
                // Messaging style notification (WhatsApp, Telegram...)
                if (isSummary) {
                    // Take the total declared count from summary
                    if (sbn.notification.number > 0) {
                        summaryUnread = max(summaryUnread, sbn.notification.number)
                    }
                } else {
                    // Individual notification: accumulate real message count
                    individualUnread += msg.unreadCount
                    for (m in msg.messages) messagePairs.add(sbn.postTime to m)
                }
                
                if (sbn.postTime > latestWhen) {
                    latestWhen = sbn.postTime
                    latestText = msg.lastText
                    latestSender = msg.lastSender
                }
            } else {
                // Standard notification
                if (!isSummary) {
                    individualUnread += sbn.notification.number.coerceAtLeast(1)
                    if (!stdText.isNullOrBlank()) {
                        val formatted = if (!stdTitle.isNullOrBlank()) "$stdTitle: $stdText" else stdText
                        messagePairs.add(sbn.postTime to formatted)
                    }
                } else {
                    // Standard summary without messaging style
                    val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    if (lines != null && lines.isNotEmpty()) {
                        for (line in lines) if (!line.isNullOrBlank()) messagePairs.add(sbn.postTime to line.toString())
                        if (summaryUnread == 0) summaryUnread = lines.size
                    } else if (summaryUnread == 0 && sbn.notification.number > 0) {
                        summaryUnread = sbn.notification.number
                    }
                }
                
                if (sbn.postTime > latestWhen && !stdText.isNullOrBlank()) {
                    latestWhen = sbn.postTime
                    latestText = stdText
                    latestSender = stdTitle
                }
            }
        }

        // --- FINAL RESOLUTION ---
        messagePairs.sortBy { it.first }
        val finalMessages = messagePairs.takeLast(10).map { it.second }
        
        // Count resolution: take the max between summary total and individual sum. 
        // This avoids doubling (2+2=4) and ensures if one is 0, the other "wins".
        val finalCount = max(summaryUnread, individualUnread)

        Log.d("MetroNotif", "REPUBLISH: $pkg -> finalCount=$finalCount, finalMsgCount=${finalMessages.size}")

        if (finalCount <= 0 && finalMessages.isEmpty()) {
            Log.d("MetroNotif", "REPUBLISH: $pkg resolved to nothing, clearing.")
            broadcast(pkg, null, null, 0, removed = true, messages = emptyList())
        } else {
            if (isSensitivePackage(pkg)) {
                broadcast(pkg, null, null, finalCount, removed = false, messages = emptyList())
            } else {
                broadcast(pkg, latestSender, latestText, finalCount, removed = false, messages = finalMessages)
            }
        }
    }

    private fun broadcast(pkg: String, title: String?, body: String?, count: Int,
                          removed: Boolean, messages: List<String> = emptyList()) {
        Log.d("MetroNotif", "Broadcasting update for $pkg: count=$count")
        
        // 1. Notify static listeners (most reliable in-process method)
        mainHandler.post {
            for (l in staticListeners) {
                l.onNotificationUpdate(pkg, title, body, count, removed, messages)
            }
        }

        // 2. Keep broadcast for backward compatibility or other uses
        val i = Intent(ACTION_NOTIFICATION_UPDATE).apply {
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_COUNT, count)
            putExtra(EXTRA_REMOVED, removed)
            putStringArrayListExtra(EXTRA_MESSAGES, ArrayList(messages))
            setPackage(packageName)
        }
        sendBroadcast(i)
    }

    // ---------- Media session → MediaInfoCache ----------------------------

    private fun rebindControllers(sessions: List<MediaController>) {
        val seen = mutableSetOf<String>()
        for (c in sessions) {
            val pkg = c.packageName
            if (pkg in seen) continue  // one session per package
            seen += pkg

            val existing = controllers[pkg]
            if (existing == c) {
                // same controller, nothing to do
            } else {
                // new (or changed) controller → unbind old, bind this
                if (existing != null) unbindController(existing)
                bindController(c)
            }
            // Push current state (important if session already existed when we attached)
            pushSnapshot(c)
        }
        // Remove controllers for packages no longer in the list
        val stale = controllers.keys - seen
        for (pkg in stale) {
            controllers[pkg]?.let { unbindController(it) }
            MediaInfoCache.remove(pkg)
        }
    }

    private fun bindController(c: MediaController) {
        val pkg = c.packageName
        controllers[pkg] = c
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { pushSnapshot(c) }
            override fun onPlaybackStateChanged(state: PlaybackState?) { pushSnapshot(c) }
            override fun onSessionDestroyed() {
                unbindController(c)
                MediaInfoCache.remove(pkg)
            }
        }
        callbacks[c] = cb
        runCatching { c.registerCallback(cb, mainHandler) }
    }

    private fun unbindController(c: MediaController) {
        val cb = callbacks.remove(c)
        if (cb != null) runCatching { c.unregisterCallback(cb) }
        controllers.remove(c.packageName)
    }

    private fun pushSnapshot(c: MediaController) {
        val metadata = runCatching { c.metadata }.getOrNull()
        val state = runCatching { c.playbackState }.getOrNull()

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)

        // Album art: try richest keys first
        val rawArt: Bitmap? =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val albumArt = rawArt?.let { scaleForTile(it) }

        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: 0L
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        // Ignore "empty" sessions without title or art: some apps publish placeholder sessions
        if (title.isNullOrBlank() && artist.isNullOrBlank() && albumArt == null) {
            MediaInfoCache.remove(c.packageName)
            return
        }

        MediaInfoCache.update(
            MediaInfoCache.MediaInfo(
                packageName = c.packageName,
                title = title,
                artist = artist,
                albumArt = albumArt,
                positionMs = max(0L, position),
                durationMs = max(0L, duration),
                isPlaying = isPlaying,
                positionCapturedAt = SystemClock.elapsedRealtime()
            )
        )
    }

    /** Scales the cover to max 512px on the longest side to save memory. */
    private fun scaleForTile(src: Bitmap): Bitmap {
        val maxSide = 512
        val w = src.width; val h = src.height
        val maxCur = max(w, h)
        if (maxCur <= maxSide) return src
        val scale = maxSide.toFloat() / maxCur
        val nw = max(1, (w * scale).toInt())
        val nh = max(1, (h * scale).toInt())
        return runCatching { Bitmap.createScaledBitmap(src, nw, nh, true) }.getOrDefault(src)
    }

    companion object {
        const val ACTION_NOTIFICATION_UPDATE = "com.metrolauncher.NOTIFICATION_UPDATE"
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_COUNT = "count"
        const val EXTRA_REMOVED = "removed"
        /** List<String> with the list of unread messages for the slideshow. */
        const val EXTRA_MESSAGES = "messages"

        /** Keywords in the package name that mark an app as "sensitive" — its 
         *  notification content is masked (see isSensitivePackage). */
        private val SENSITIVE_KEYWORDS = setOf(
            "bank", "banking", "banc", "finance", "financial", "wallet", "pay", "paypal",
            "revolut", "satispay", "postepay", "n26", "hype",
            "mail", "outlook", "gmail", "yahoo", "thunderbird", "mailbox", "fairemail",
            "bluemail", "k9", "k-9",
            "password", "passw", "keepass", "bitwarden", "1password", "lastpass",
            "authenticator", "authy", "auth"
        )

        /** Exact match (for cases where keywords are too generic to rely on contains). */
        private val SENSITIVE_PKG_EXACT = setOf(
            "com.intesasanpaolo.mobile",
            "it.bnl.apps.banking",
            "it.unicredit",
            "com.bpm.mybank",
            "it.popso.SCRIGNOAPP",
            "it.popularesondrio.scrigno",
            "com.fineco.it",
            "it.bancomediolanum"
        )

        /** Active service instance, to send media commands from outside (tile tap). */
        @Volatile private var instance: NotificationListener? = null
        private val staticListeners = mutableListOf<Listener>()

        fun addListener(l: Listener) {
            staticListeners.add(l)
            Log.d("MetroNotif", "Listener added, total: ${staticListeners.size}")
        }

        fun removeListener(l: Listener) {
            staticListeners.remove(l)
            Log.d("MetroNotif", "Listener removed, total: ${staticListeners.size}")
        }

        /** Returns the set of packages currently posting non-ongoing notifications.
         *  Used by MainActivity to detect stale tile state (tiles still showing a
         *  count after the user dismissed all notifications for that app). */
        fun getActivePackages(): Set<String> {
            val svc = instance ?: return emptySet()
            return runCatching {
                svc.activeNotifications
                    ?.filter {
                        (it.notification.flags and Notification.FLAG_ONGOING_EVENT) == 0 &&
                        (it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) == 0 &&
                        (it.notification.flags and Notification.FLAG_NO_CLEAR) == 0
                    }
                    ?.map { it.packageName }
                    ?.toSet()
                    .orEmpty()
            }.getOrDefault(emptySet())
        }

        /** Forces re-sending of all current notification broadcasts.
         *  Useful when MainActivity is reborn and lost transient tile data. */
        fun requestRebind() {
            instance?.let { svc ->
                Log.d("MetroNotif", "Manual rebind requested")
                try {
                    val active = svc.activeNotifications
                    Log.d("MetroNotif", "Active notifications count: ${active?.size ?: "null"}")
                    active?.map { it.packageName }?.distinct()?.forEach { pkg ->
                        svc.republishAggregateFor(pkg)
                    }
                } catch (e: Exception) {
                    Log.e("MetroNotif", "Error during manual rebind", e)
                }
            } ?: Log.w("MetroNotif", "Manual rebind failed: instance is null")
        }

        /**
         * Sends a transport command to the MediaController of the specified package.
         * Returns true if the command was sent, false if the controller does not exist 
         * (e.g., app not playing, or service not connected).
         */
        fun sendTransport(pkg: String, action: TransportAction): Boolean {
            val svc = instance ?: return false
            val controller = svc.controllers[pkg] ?: return false
            val tc = runCatching { controller.transportControls }.getOrNull() ?: return false
            runCatching {
                when (action) {
                    TransportAction.PLAY_PAUSE -> {
                        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                        if (playing) tc.pause() else tc.play()
                    }
                    TransportAction.NEXT -> tc.skipToNext()
                    TransportAction.PREVIOUS -> tc.skipToPrevious()
                }
            }.onFailure { return false }
            return true
        }

        enum class TransportAction { PLAY_PAUSE, NEXT, PREVIOUS }
    }
}
