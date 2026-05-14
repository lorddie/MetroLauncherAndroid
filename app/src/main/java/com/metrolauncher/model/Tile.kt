package com.metrolauncher.model

/**
 * A tile placed on the Start screen.
 * `accentColor` is the background color (can be ARGB, supports transparency to show wallpaper).
 * `row`/`col` are the positions in the 4-column grid.
 * `customLabel` overrides the default label if present.
 */
data class Tile(
    val id: String = "",                       // unique id (uuid)
    val packageName: String = "",
    val activityName: String = "",
    var size: TileSize = TileSize.MEDIUM,
    var row: Int = 0,
    var col: Int = 0,
    var accentColor: Int = 0xFF0078D7.toInt(),  // Windows blue by default
    var customLabel: String? = null,
    var showLabel: Boolean = true,
    var transparentBackground: Boolean = false,
    var notificationsDisabled: Boolean = false,
    var mediaDisabled: Boolean = false,
    /** Special tile type: replaces standard rendering (CALENDAR, GALLERY, ...). */
    var kind: TileKind = TileKind.APP,
    /** For GALLERY: persistent URI of a content-tree (document tree) with photos. */
    var gallerySourceUri: String? = null, // shows wallpaper as background
    /** For WEATHER: Manual location name entered by the user (e.g., "Milan"). 
     *  If null, uses automatic GPS. */
    var weatherLocationOverride: String? = null,
    var folderItems: MutableList<Tile> = mutableListOf(),
    var webUrl: String? = null,
    var webIconPath: String? = null,
    // Live tile fields (populated by NotificationListener, not manually persisted)
    @Transient var liveTitle: String? = null,
    @Transient var liveBody: String? = null,
    @Transient var liveCount: Int = 0,
    /** List of unread messages for the slideshow (apps with multiple notifications). */
    @Transient var liveMessages: List<String> = emptyList(),
    /** Current index of the slideshow. Advanced by TileGridLayout every ~3 seconds. */
    @Transient var liveMessageIndex: Int = 0
) {
    val componentKey: String get() = "$packageName/$activityName"
}
