package com.metrolauncher.activity

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import com.metrolauncher.R
import com.metrolauncher.model.AppInfo
import com.metrolauncher.model.Tile
import com.metrolauncher.model.TileSize
import com.metrolauncher.service.NotificationListener
import com.metrolauncher.util.AppLoader
import com.metrolauncher.util.ColorUtils
import com.metrolauncher.util.GridPacker
import com.metrolauncher.util.MediaInfoCache
import com.metrolauncher.util.Prefs
import com.metrolauncher.util.TileStorage
import com.metrolauncher.view.TileGridLayout
import com.metrolauncher.view.TileView
import kotlinx.coroutines.*
import java.util.UUID

class MainActivity : AppCompatActivity(), NotificationListener.Listener {

    private val storage: TileStorage by lazy { TileStorage(this) }
    private val tiles = mutableListOf<Tile>()
    private val iconCache = mutableMapOf<String, Drawable?>()
    private val scope = MainScope()

    private lateinit var tileGrid: TileGridLayout
    private lateinit var scrollView: NestedScrollView
    private lateinit var wallpaperView: ImageView
    private lateinit var hintArrow: TextView
    private lateinit var searchBar: com.metrolauncher.view.SearchBarView
    private lateinit var pager: androidx.viewpager2.widget.ViewPager2

    private var editMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaListener = MediaInfoCache.Listener { pkg ->
        mainHandler.post { if (::tileGrid.isInitialized) { tileGrid.updateMediaForPackage(pkg, MediaInfoCache.get(pkg)); ensureMediaTicker() } }
    }
    private val mediaTicker = object : Runnable {
        override fun run() { if (::tileGrid.isInitialized && tileGrid.hasAnyMediaTile() && ::pager.isInitialized && pager.currentItem == LauncherPagerAdapter.PAGE_START && !isAppIdle()) tileGrid.tickMediaProgress(); mainHandler.postDelayed(this, 1000L) }
    }
    private var tickerRunning = false
    private fun ensureMediaTicker() { if (!tickerRunning && ::tileGrid.isInitialized && tileGrid.hasAnyMediaTile()) { tickerRunning = true; mainHandler.post(mediaTicker) } }

    private val slideshowTicker = object : Runnable {
        override fun run() {
            if (!::tileGrid.isInitialized) { slideshowRunning = false; return }
            val isStartVisible = ::pager.isInitialized && pager.currentItem == LauncherPagerAdapter.PAGE_START
            val canAnimate = isStartVisible && !isAppIdle()
            
            if (canAnimate) {
                // Large/Wide tile management
                tileGrid.updateWideLargeCycles()

                // Medium tile management (new Icon/Preview cycle)
                tileGrid.updateLiveCycles()
            }
            
            mainHandler.postDelayed(this, 1000L) // Frequency increased to 1s for precise timing management
        }
    }
    private var slideshowRunning = false
    private fun ensureSlideshowTicker() {
        if (!slideshowRunning && ::tileGrid.isInitialized) {
            val hasContent = tiles.any { 
                it.liveMessages.size >= 2 ||
                (it.size == TileSize.MEDIUM && (!it.liveBody.isNullOrBlank() || !it.liveTitle.isNullOrBlank() || it.liveMessages.isNotEmpty()))
            }
            if (hasContent) {
                slideshowRunning = true
                mainHandler.post(slideshowTicker)
            }
        }
    }

    private val galleryTicker = object : Runnable {
        override fun run() {
            // Optimization Point 4: Load photos only if Start is visible
            val isStartVisible = ::pager.isInitialized && pager.currentItem == LauncherPagerAdapter.PAGE_START
            if (isStartVisible && !isAppIdle()) {
                scope.launch {
                    for (t in tiles) {
                        if (t.kind != com.metrolauncher.model.TileKind.GALLERY || t.gallerySourceUri == null) continue
                        val images = withContext(Dispatchers.IO) { com.metrolauncher.util.GalleryProvider.listImages(this@MainActivity, t.gallerySourceUri!!) }
                        if (images.isEmpty()) continue
                        val curIdx = galleryIndexByTile[t.id] ?: 0; val nextIdx = (curIdx + 1) % images.size; galleryIndexByTile[t.id] = nextIdx
                        val bmp = withContext(Dispatchers.IO) { com.metrolauncher.util.GalleryProvider.loadBitmap(this@MainActivity, images[nextIdx], 800) }
                        if (::tileGrid.isInitialized) tileGrid.flipTile(t.id) { tileGrid.setGalleryBitmap(t.id, bmp); galleryPhotoStartTime[t.id] = android.os.SystemClock.elapsedRealtime() }
                    }
                }
            }
            mainHandler.postDelayed(this, 15000L)
        }
    }
    private val galleryIndexByTile = mutableMapOf<String, Int>()
    private val galleryPhotoStartTime = mutableMapOf<String, Long>()
    private val galleryPanTicker = object : Runnable {
        override fun run() {
            if (!::tileGrid.isInitialized) return
            val now = android.os.SystemClock.elapsedRealtime()
            var anyGallery = false; val isStartVisible = ::pager.isInitialized && pager.currentItem == LauncherPagerAdapter.PAGE_START
            for (t in tiles) {
                if (t.kind != com.metrolauncher.model.TileKind.GALLERY) continue
                anyGallery = true
                if (isStartVisible) {
                    val start = galleryPhotoStartTime[t.id] ?: continue; val progress = ((now - start).toFloat() / 15000L).coerceIn(0f, 1f)
                    val eased = ((progress - 0.15f) / 0.7f).coerceIn(0f, 1f); tileGrid.setGalleryPanProgress(t.id, eased)
                }
            }
            if (anyGallery) mainHandler.postDelayed(this, 60L)
        }
    }

    private val weatherFetchTicker = object : Runnable {
        override fun run() {
            if (::pager.isInitialized && pager.currentItem == LauncherPagerAdapter.PAGE_START) scope.launch {
                if (::tileGrid.isInitialized) for (t in tiles) if (t.kind == com.metrolauncher.model.TileKind.WEATHER) {
                    val snap = com.metrolauncher.util.WeatherProvider.fetchNow(this@MainActivity, t.weatherLocationOverride)
                    if (snap != null) tileGrid.setWeatherSnapshot(t.id, snap)
                }
            }
            mainHandler.postDelayed(this, 60L * 60 * 1000)
        }
    }
    private val weatherAnimTicker = object : Runnable {
        override fun run() {
            if (::tileGrid.isInitialized && ::pager.isInitialized && pager.currentItem == LauncherPagerAdapter.PAGE_START) {
                // We always run the ticker to allow mode switching (Anim/Info),
                // but TileView will decide whether to perform a heavy invalidate() or not.
                tileGrid.tickWeatherAnimations()
                tileGrid.updateWeatherCycles()
            }
            mainHandler.postDelayed(this, 1000L) // Increased interval: 1s is enough for mode switching
        }
    }

    /** Advances the 15-game puzzle of all visible FOLDER tiles every 2.5s. */
    private val folderPuzzleTicker = object : Runnable {
        override fun run() {
            if (::tileGrid.isInitialized && ::pager.isInitialized
                && pager.currentItem == LauncherPagerAdapter.PAGE_START && !isAppIdle()) {
                tileGrid.tickAllFolderPuzzles()
            }
            mainHandler.postDelayed(this, 2500L)
        }
    }

    private fun ensureSpecialTileTickers() {
        mainHandler.removeCallbacks(weatherFetchTicker); mainHandler.removeCallbacks(weatherAnimTicker)
        if (tiles.any { it.kind == com.metrolauncher.model.TileKind.WEATHER }) { mainHandler.post(weatherFetchTicker); mainHandler.post(weatherAnimTicker) }
        mainHandler.removeCallbacks(galleryTicker); mainHandler.removeCallbacks(galleryPanTicker)
        if (tiles.any { it.kind == com.metrolauncher.model.TileKind.GALLERY && it.gallerySourceUri != null }) { mainHandler.post(galleryTicker); mainHandler.post(galleryPanTicker) }
        mainHandler.removeCallbacks(folderPuzzleTicker)
        if (tiles.any { it.kind == com.metrolauncher.model.TileKind.FOLDER && it.folderItems.size >= 2 }) { mainHandler.postDelayed(folderPuzzleTicker, 2500L) }
    }

    private fun stopAllTickers() {
        mainHandler.removeCallbacks(mediaTicker)
        tickerRunning = false
        mainHandler.removeCallbacks(slideshowTicker)
        slideshowRunning = false
        mainHandler.removeCallbacks(galleryTicker)
        mainHandler.removeCallbacks(galleryPanTicker)
        mainHandler.removeCallbacks(weatherFetchTicker)
        mainHandler.removeCallbacks(weatherAnimTicker)
        mainHandler.removeCallbacks(folderPuzzleTicker)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        window.statusBarColor = 0; window.navigationBarColor = 0
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        wallpaperView = findViewById(R.id.wallpaper_view); pager = findViewById(R.id.pager)
        pager.adapter = LauncherPagerAdapter(this); pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { 
                if (position == LauncherPagerAdapter.PAGE_DRAWER && editMode) exitEditMode()
                if (position == LauncherPagerAdapter.PAGE_START) {
                    // Reset search when returning to Start screen
                    supportFragmentManager.fragments.filterIsInstance<DrawerFragment>()
                        .forEach { it.resetSearch() }
                }
            }
        })
        setupWallpaper(); loadTileDataOnly(); maybePromptNotificationAccess()
    }

    fun onStartFragmentReady(fragment: com.metrolauncher.activity.StartFragment) {
        tileGrid = fragment.tileGrid; scrollView = fragment.scrollView; hintArrow = fragment.hintArrow; searchBar = fragment.searchBar
        val pullDown = fragment.pullDownLayout
        fragment.searchBar.onActionClicked = { action ->
            if (action == com.metrolauncher.view.SearchBarView.Action.SEARCH) {
                if (pullDown.isOpen) { launchGoogleSearch(); scope.launch { delay(300); pullDown.close() } }
            } else {
                when (action) {
                    com.metrolauncher.view.SearchBarView.Action.LENS -> launchGoogleLens()
                    com.metrolauncher.view.SearchBarView.Action.MIC -> launchVoiceSearch()
                    com.metrolauncher.view.SearchBarView.Action.GEMINI -> launchGemini()
                    else -> {}
                }
                scope.launch { delay(300); pullDown.close() }
            }
        }
        tileGrid.onTileDroppedOnTile = { dragged, target -> createOrUpdateFolder(dragged, target) }
        tileGrid.onTileClickListener = { tile ->
            if (editMode) showTileMenu(tile)
            else if (tile.kind == com.metrolauncher.model.TileKind.FOLDER) tileGrid.expandFolder(tile.id) { iconFor(it) }
            else if (tile.kind == com.metrolauncher.model.TileKind.WEB_LINK) launchWebLink(tile)
            else launchApp(tile.packageName, tile.activityName)
        }
        // Clicking on a mini-tile inside an expanded folder: launch the app, close folder
        tileGrid.onFolderTileClick = { mini ->
            tileGrid.collapseExpandedFolder()
            launchApp(mini.packageName, mini.activityName)
        }
        // When a folder expands, we scroll to center the gap inside the viewport
        tileGrid.onFolderExpanded = { folderTop, expansionHeight ->
            val viewportH = scrollView.height
            // folderTop is relative to TileGridLayout, which starts after the ScrollView's paddingTop (36dp).
            // The target scroll-Y must compensate for the padding.
            val realTop = folderTop + scrollView.paddingTop
            val wantedScrollY = realTop - viewportH / 4
            val maxScroll = (scrollView.getChildAt(0)?.height ?: 0).minus(viewportH)
                .coerceAtLeast(0)
            scrollView.smoothScrollTo(0, wantedScrollY.coerceIn(0, maxScroll))
        }
        // Long-press on mini-tile = drag-out. Remove from folder, add to tiles[] 
        // in free position, and continue programmatic drag.
        tileGrid.onMiniTileLongPress = dragOut@{ mini, miniView, localX, localY ->
            val folderId = tileGrid.expandedFolderId() ?: return@dragOut
            val folder = tiles.firstOrNull { it.id == folderId } ?: return@dragOut
            // 1. Remove from folder
            folder.folderItems.removeAll { it.id == mini.id }
            // 2. Promote to normal tile: kind=APP, "free" position just outside the folder
            //    (if the folder remains non-empty). If the folder is now empty, we replace it with
            //    the new promoted tile.
            val promoted = mini.copy(kind = com.metrolauncher.model.TileKind.APP)
            if (folder.folderItems.isEmpty()) {
                // Replace folder with promoted tile, same position/size
                promoted.row = folder.row; promoted.col = folder.col; promoted.size = folder.size
                val idx = tiles.indexOf(folder); if (idx >= 0) tiles[idx] = promoted
            } else {
                // Add at the bottom, autoLayout will find a slot
                promoted.row = (tiles.maxOfOrNull { it.row + it.size.rows } ?: 0)
                promoted.col = 0
                tiles.add(promoted)
            }
            GridPacker.autoLayout(tiles, tileGrid.columns)
            storage.save(tiles)
            // 3. Rebuild grid (closes expanded folder, recreates tiles)
            tileGrid.setTiles(tiles) { iconFor(it) }
            // 4. Start programmatic drag on the new tile: the user's finger is still
            //    on the old mini-tile, localX/localY coordinates indicate where it is
            //    relative to the mini's top-left. Use post to ensure the layout
            //    has been applied before attempting to find the new view.
            tileGrid.post {
                tileGrid.startDragOnTile(promoted.id, localX, localY)
            }
        }
        tileGrid.onTileLongClickListener = { _, _ -> enterEditMode(); true }
        tileGrid.onTileMoved = { _ -> tiles.sortWith(compareBy({ it.row }, { it.col })); storage.save(tiles) }
        tileGrid.onEditModeEntered = { enterEditMode() }
        tileGrid.onEmptyTapInEditMode = { exitEditMode() }
        tileGrid.onDragStart = { scrollView.requestDisallowInterceptTouchEvent(true); pager.isUserInputEnabled = false }
        tileGrid.onDragEnd = { scrollView.requestDisallowInterceptTouchEvent(false); pager.isUserInputEnabled = true }
        tileGrid.gap = resources.getDimensionPixelSize(R.dimen.tile_gap)
        
        val mask = fragment.tileMask; val dm = resources.displayMetrics
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            val sy = scrollView.scrollY; tileGrid.scrollOffsetY = sy
            mask.translationY = 0f; mask.setScrollY(-sy.toFloat() + scrollView.paddingTop.toFloat())
            val mode = Prefs.string(this, Prefs.KEY_BACKGROUND_MODE, "normal")
            val maxP = dm.heightPixels * 0.20f
            if (mode == "tiles_only") wallpaperView.translationY = (-sy * 0.10f).coerceIn(-maxP, maxP)
            else wallpaperView.translationY = (-sy * 0.15f).coerceIn(-maxP, maxP)
        }
        tileGrid.onTileRectsChanged = { mask.setTileRects(it) }
        hintArrow.setOnClickListener { goToDrawerPage() }
        applyUserPreferences(); tileGrid.setTiles(tiles) { iconFor(it) }
        hydrateMediaOnAllTiles(); ensureSpecialTileTickers()
    }

    private fun createOrUpdateFolder(dragged: Tile, target: Tile) {
        if (target.kind == com.metrolauncher.model.TileKind.FOLDER) {
            // Add to existing folder: no prompt, just enqueue
            target.folderItems.add(dragged.copy())
            tiles.removeAll { it.id == dragged.id }
            relayoutAll()
        } else {
            // New folder: ask for the name BEFORE confirming the merge
            val input = android.widget.EditText(this).apply {
                hint = getString(R.string.folder_name_hint)
                setText(getString(R.string.default_folder_name))
                setSelection(0, text.length)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog)
                .setTitle(R.string.create_folder_title)
                .setMessage(R.string.create_folder_msg)
                .setView(input)
                .setPositiveButton(R.string.create_button) { _, _ ->
                    val name = input.text.toString().ifBlank { getString(R.string.default_folder_name) }
                    val newFolder = Tile(
                        id = UUID.randomUUID().toString(),
                        kind = com.metrolauncher.model.TileKind.FOLDER,
                        row = target.row, col = target.col,
                        size = TileSize.MEDIUM,    // folder always MEDIUM
                        accentColor = target.accentColor,
                        customLabel = name,
                        folderItems = mutableListOf(target.copy(), dragged.copy())
                    )
                    val idx = tiles.indexOf(target); if (idx >= 0) tiles[idx] = newFolder else tiles.add(newFolder)
                    tiles.removeAll { it.id == dragged.id }
                    relayoutAll()
                }
                .setNegativeButton(R.string.cancel_button, null)
                .setOnCancelListener {
                    // Drag canceled: redo layout so the dragged tile returns to its place
                    relayoutAll()
                }
                .show()
        }
    }

    private fun openFolder(folder: Tile) {
        val labels = folder.folderItems.map { it.customLabel ?: it.packageName }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog).setTitle(folder.customLabel ?: "Folder").setItems(labels) { _, w ->
            val item = folder.folderItems[w]; launchApp(item.packageName, item.activityName)
        }.setNeutralButton("Remove from folder") { _, _ ->
            showRemoveFromFolderDialog(folder)
        }.show()
    }

    private fun showRemoveFromFolderDialog(folder: Tile) {
        val labels = folder.folderItems.map { it.customLabel ?: it.packageName }.toTypedArray()
        AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog).setTitle("Remove app").setItems(labels) { _, w ->
            val item = folder.folderItems.removeAt(w); tiles.add(item); relayoutAll()
        }.show()
    }

    private fun relayoutAll() {
        val cols = if (::tileGrid.isInitialized) tileGrid.columns else Prefs.gridColumns(this)
        GridPacker.autoLayout(tiles, cols)
        storage.save(tiles)
        loadTiles()
    }

    override fun onNotificationUpdate(pkg: String, title: String?, body: String?, count: Int, removed: Boolean, messages: List<String>) {
        mainHandler.post {
            tiles.forEach { t ->
                if (t.packageName == pkg || t.folderItems.any { it.packageName == pkg }) {
                    if (t.kind == com.metrolauncher.model.TileKind.FOLDER) {
                        t.liveCount = t.folderItems.sumOf { if (it.packageName == pkg) count else it.liveCount }
                    } else if (!t.notificationsDisabled) {
                        if (removed) {
                            // Reset COMPLETO: senza azzerare anche title/body, la tile
                            // resta in "modalità live" mostrando contenuto vecchio.
                            t.liveCount = 0; t.liveMessages = emptyList()
                            t.liveTitle = null; t.liveBody = null
                            t.liveMessageIndex = 0
                        } else {
                            t.liveTitle = title; t.liveBody = body; t.liveCount = count
                            t.liveMessages = messages
                            if (::tileGrid.isInitialized) tileGrid.flipTile(t.id)
                        }
                    }
                    if (::tileGrid.isInitialized) tileGrid.updateLiveContent(t.id, t.liveTitle, t.liveBody, t.liveCount)
                }
            }
            ensureSlideshowTicker()
        }
    }

    /**
     * Sweep delle "notifiche fantasma": confronta i package con notifiche attive contro
     * le tile che mostrano ancora contenuto live, e ripulisce quelle che non hanno più
     * notifiche corrispondenti.
     *
     * Indispensabile dopo onResume() perché `requestRebind` republish-a solo per i
     * package con notifiche attive — se WhatsApp aveva 3 messaggi non letti e l'utente
     * li ha tutti aperti, WhatsApp non è più in `activeNotifications` e la tile
     * resterebbe a "3" all'infinito.
     */
    private fun sweepGhostNotifications() {
        val activePkgs = NotificationListener.getActivePackages()
        var anyCleared = false
        tiles.forEach { t ->
            val hasLiveContent = t.liveCount > 0 ||
                                  t.liveMessages.isNotEmpty() ||
                                  !t.liveTitle.isNullOrBlank() ||
                                  !t.liveBody.isNullOrBlank()
            if (!hasLiveContent) return@forEach
            if (t.kind == com.metrolauncher.model.TileKind.FOLDER) {
                // Per le folder: aggrega solo i conteggi degli items il cui package è
                // ancora attivo
                val active = t.folderItems.filter { it.packageName in activePkgs }
                t.liveCount = active.sumOf { it.liveCount }
                if (::tileGrid.isInitialized) tileGrid.updateLiveContent(t.id, null, null, t.liveCount)
                anyCleared = true
            } else if (t.packageName !in activePkgs) {
                // App non più attiva → reset completo
                t.liveCount = 0; t.liveMessages = emptyList()
                t.liveTitle = null; t.liveBody = null; t.liveMessageIndex = 0
                if (::tileGrid.isInitialized) tileGrid.updateLiveContent(t.id, null, null, 0)
                anyCleared = true
            }
        }
        if (anyCleared) ensureSlideshowTicker()
    }

    fun goToStartPage() { if (::pager.isInitialized) pager.currentItem = LauncherPagerAdapter.PAGE_START }
    fun goToDrawerPage() { if (::pager.isInitialized) pager.currentItem = LauncherPagerAdapter.PAGE_DRAWER }

    private fun loadTileDataOnly() {
        val saved = storage.load(); tiles.clear(); tiles.addAll(saved)
        if (GridPacker.sanitize(tiles, Prefs.gridColumns(this))) storage.save(tiles)
    }

    private fun loadTiles() {
        loadTileDataOnly(); if (!::tileGrid.isInitialized) return
        tileGrid.setTiles(tiles) { iconFor(it) }; hydrateMediaOnAllTiles()
    }

    private fun hydrateMediaOnAllTiles() {
        if (!::tileGrid.isInitialized) return
        for (pkg in MediaInfoCache.activePackages()) tileGrid.updateMediaForPackage(pkg, MediaInfoCache.get(pkg))
        ensureMediaTicker(); hydrateSpecialTiles(); hydrateWebLinkIcons()
    }

    private fun hydrateSpecialTiles() {
        com.metrolauncher.util.WeatherProvider.cachedSnapshot(this)?.let { cached ->
            tiles.filter { it.kind == com.metrolauncher.model.TileKind.WEATHER }.forEach { tileGrid.setWeatherSnapshot(it.id, cached) }
        }
        scope.launch {
            if (tiles.any { it.kind == com.metrolauncher.model.TileKind.CALENDAR }) {
                val events = withContext(Dispatchers.IO) { com.metrolauncher.util.CalendarProvider.upcomingEvents(this@MainActivity) }
                tiles.filter { it.kind == com.metrolauncher.model.TileKind.CALENDAR }.forEach { tileGrid.setCalendarEvents(it.id, events) }
            }
            tiles.filter { it.kind == com.metrolauncher.model.TileKind.WEATHER }.forEach {
                val snap = com.metrolauncher.util.WeatherProvider.fetchNow(this@MainActivity, it.weatherLocationOverride)
                if (snap != null) tileGrid.setWeatherSnapshot(it.id, snap)
            }
        }
    }

    private fun iconFor(tile: Tile): Drawable? {
        val key = if (tile.kind == com.metrolauncher.model.TileKind.WEB_LINK) tile.id else tile.componentKey
        iconCache[key]?.let { return it }
        val icon = if (tile.kind == com.metrolauncher.model.TileKind.WEB_LINK) {
            com.metrolauncher.util.FaviconCache.load(this, tile.id)?.let {
                android.graphics.drawable.BitmapDrawable(resources, it)
            }
        } else {
            AppLoader.loadIcon(this, tile.packageName, tile.activityName)
        }
        iconCache[key] = icon; return icon
    }

    private fun launchApp(pkg: String, activity: String) {
        runCatching {
            val i = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(pkg, activity)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(i)
        }.onFailure { tiles.firstOrNull { it.packageName == pkg && it.activityName == activity }?.let { unpinTile(it) } }
    }

    private fun launchWebLink(tile: Tile) {
        val url = tile.webUrl ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun pinApp(app: AppInfo) {
        if (tiles.any { it.componentKey == app.componentKey }) return
        val icon = AppLoader.loadIcon(this, app.packageName, app.activityName)
        val newTile = Tile(id = UUID.randomUUID().toString(), packageName = app.packageName, activityName = app.activityName, size = TileSize.MEDIUM, accentColor = ColorUtils.dominantColor(icon), customLabel = app.label)
        addTileToStart(newTile, icon)
    }

    private fun addTileToStart(tile: Tile, icon: Drawable?) {
        val cols = if (::tileGrid.isInitialized) tileGrid.columns else Prefs.gridColumns(this)
        val (r, c) = GridPacker.findFreeSlot(tiles, tile.size, cols)
        tile.row = r; tile.col = c; tiles.add(tile); applyUserPreferences()
        if (::tileGrid.isInitialized) tileGrid.addTile(tile, icon); storage.save(tiles)
    }

    fun showAddWebLinkDialog() {
        val dp = resources.displayMetrics.density
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        val editLabel = android.widget.EditText(this).apply {
            hint = getString(R.string.name_hint)
            setHintTextColor(0x66FFFFFF.toInt()); setTextColor(0xFFFFFFFF.toInt()); background = null
            textSize = 18f
        }
        val editUrl = android.widget.EditText(this).apply {
            hint = getString(R.string.url_hint)
            setHintTextColor(0x66FFFFFF.toInt()); setTextColor(0xFFFFFFFF.toInt()); background = null
            textSize = 16f
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        }
        layout.addView(editUrl); layout.addView(editLabel)

        AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog)
            .setTitle(R.string.add_web_link_title).setView(layout)
            .setPositiveButton(R.string.add_button) { _, _ ->
                val url = editUrl.text.toString().trim()
                if (url.isNotBlank()) addWebLink(
                    label = editLabel.text.toString().trim(),
                    url = if (url.startsWith("http")) url else "https://$url"
                )
            }
            .setNegativeButton(android.R.string.cancel, null).show()

        editUrl.requestFocus()
    }

    /** Downloads the favicon in the background and updates the tile when ready. */
    private fun addWebLink(label: String, url: String) {
        val tileId = UUID.randomUUID().toString()
        val domain = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)
        val name = label.ifBlank { domain }
        val newTile = Tile(
            id = tileId, kind = com.metrolauncher.model.TileKind.WEB_LINK,
            webUrl = url, customLabel = name, size = TileSize.MEDIUM
        )
        addTileToStart(newTile, null)

        // Download favicon in background and update tile as soon as ready
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                com.metrolauncher.util.FaviconCache.fetchAndSave(this@MainActivity, tileId, url)
            }
            if (bmp != null && ::tileGrid.isInitialized) {
                iconCache[tileId] = android.graphics.drawable.BitmapDrawable(resources, bmp)
                tileGrid.setWebIconForTile(tileId, bmp)
            }
        }
    }

    /** Loads saved favicons for all WEB_LINK tiles. Called after each setTiles (startup, reload, etc.). */
    private fun hydrateWebLinkIcons() {
        if (!::tileGrid.isInitialized) return
        scope.launch {
            for (tile in tiles) {
                if (tile.kind != com.metrolauncher.model.TileKind.WEB_LINK) continue
                val bmp = withContext(Dispatchers.IO) {
                    com.metrolauncher.util.FaviconCache.load(this@MainActivity, tile.id)
                }
                if (bmp != null) {
                    iconCache[tile.id] = android.graphics.drawable.BitmapDrawable(resources, bmp)
                    tileGrid.setWebIconForTile(tile.id, bmp)
                }
            }
        }
    }

    private fun resizeTile(tile: Tile, newSize: TileSize) { tile.size = newSize; relayoutAll() }
    private fun unpinTile(tile: Tile) {
        tiles.remove(tile)
        if (::tileGrid.isInitialized) tileGrid.removeTile(tile.id)
        storage.save(tiles)
        relayoutAll()
    }

    private fun showTileMenu(tile: Tile) {
        data class Entry(val label: String, val action: () -> Unit); val entries = mutableListOf<Entry>()
        if (tile.kind != com.metrolauncher.model.TileKind.FOLDER) {
            entries += Entry(getString(R.string.resize_small)) { resizeTile(tile, TileSize.SMALL) }
            entries += Entry(getString(R.string.resize_medium)) { resizeTile(tile, TileSize.MEDIUM) }
            entries += Entry(getString(R.string.resize_wide)) { resizeTile(tile, TileSize.WIDE) }
            entries += Entry(getString(R.string.resize_large)) { resizeTile(tile, TileSize.LARGE) }
        }
        if (tile.kind == com.metrolauncher.model.TileKind.WEATHER) {
            entries += Entry(getString(R.string.update_weather)) { scope.launch { val snap = com.metrolauncher.util.WeatherProvider.fetchNow(this@MainActivity, tile.weatherLocationOverride); if (snap != null) tileGrid.setWeatherSnapshot(tile.id, snap) } }
        }
        if (tile.kind == com.metrolauncher.model.TileKind.FOLDER) {
            entries += Entry(getString(R.string.rename_folder_title)) { promptRenameFolder(tile) }
        }
        val privLabel = if (tile.notificationsDisabled) getString(R.string.enable_notifications) else getString(R.string.disable_notifications)
        entries += Entry(privLabel) { tile.notificationsDisabled = !tile.notificationsDisabled; if (tile.notificationsDisabled) { tile.liveCount = 0; tileGrid.updateLiveContent(tile.id, null, null, 0) } else NotificationListener.requestRebind(); storage.save(tiles) }
        val mediaLabel = if (tile.mediaDisabled) getString(R.string.enable_mini_player) else getString(R.string.disable_mini_player)
        entries += Entry(mediaLabel) { tile.mediaDisabled = !tile.mediaDisabled; if (tile.mediaDisabled) tileGrid.updateMediaForPackage(tile.packageName, null) else MediaInfoCache.get(tile.packageName)?.let { tileGrid.updateMediaForPackage(tile.packageName, it) }; storage.save(tiles) }
        entries += Entry(getString(R.string.unpin)) { unpinTile(tile) }
        entries += Entry(getString(R.string.open_launcher_settings)) { startActivity(Intent(this, SettingsActivity::class.java)) }
        AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog).setTitle(tile.customLabel ?: "").setItems(entries.map { it.label }.toTypedArray()) { _, w -> entries[w].action() }.show()
    }

    private fun promptRenameFolder(folder: Tile) {
        val input = android.widget.EditText(this).apply {
            setText(folder.customLabel ?: "")
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this, R.style.Theme_MetroLauncher_Dialog)
            .setTitle(R.string.rename_folder_title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                folder.customLabel = input.text.toString().ifBlank { getString(R.string.default_folder_name) }
                tileGrid.setTiles(tiles) { iconFor(it) }; storage.save(tiles)
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun maybePromptNotificationAccess() {
        val cn = ComponentName(this, NotificationListener::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(cn) == true
        if (!enabled) startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun applyUserPreferences() {
        requestedOrientation = if (Prefs.bool(this, Prefs.KEY_LOCK_ROTATION, true)) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (!::tileGrid.isInitialized) return
        tileGrid.setColumns(Prefs.gridColumns(this)); tileGrid.setGlobalOpacity(Prefs.int(this, Prefs.KEY_TILE_OPACITY, 100) / 100f)
        val useGlobal = Prefs.bool(this, Prefs.KEY_USE_GLOBAL_COLOR, false)
        if (useGlobal) {
            val idx = Prefs.int(this, Prefs.KEY_GLOBAL_COLOR_INDEX, 0); val color = if (idx == Prefs.CUSTOM_COLOR_SENTINEL) Prefs.int(this, Prefs.KEY_GLOBAL_COLOR_CUSTOM, 0xFF0078D7.toInt()) else ColorUtils.METRO_COLORS[idx.coerceIn(0, ColorUtils.METRO_COLORS.size - 1)]
            tileGrid.setGlobalColorOverride(color); if (::searchBar.isInitialized) searchBar.barColor = color
        } else { tileGrid.setGlobalColorOverride(null); if (::searchBar.isInitialized) searchBar.barColor = 0xFF352D25.toInt() }
        tileGrid.setMonochromeIcons(Prefs.bool(this, Prefs.KEY_MONOCHROME_ICONS, false))
        tileGrid.setTextColor(if (Prefs.string(this, Prefs.KEY_TEXT_COLOR_MODE, "white") == "black") android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        applyBackgroundMode(Prefs.string(this, Prefs.KEY_BACKGROUND_MODE, "normal") ?: "normal")
    }

    private fun applyBackgroundMode(mode: String) {
        val dim = findViewById<View>(R.id.wallpaper_dim); val startFrag = runCatching { supportFragmentManager.fragments.firstOrNull { it is com.metrolauncher.activity.StartFragment } as? com.metrolauncher.activity.StartFragment }.getOrNull(); val mask = startFrag?.tileMask
        val alpha = Prefs.int(this, Prefs.KEY_TILE_OPACITY, 100) / 100f
        when (mode) {
            "black" -> { wallpaperView.visibility = View.INVISIBLE; dim?.visibility = View.INVISIBLE; mask?.setActive(false); if (::searchBar.isInitialized) searchBar.isTransparent = false; if (::tileGrid.isInitialized) { tileGrid.setWallpaperTilesOnly(false, null, 0f, 0f); tileGrid.setGlobalOpacity(alpha); if (::searchBar.isInitialized) searchBar.alphaValue = alpha } }
            "tiles_only" -> { wallpaperView.visibility = View.VISIBLE; dim?.visibility = View.INVISIBLE; mask?.setActive(true); if (::searchBar.isInitialized) searchBar.isTransparent = true; if (::tileGrid.isInitialized) { tileGrid.setWallpaperTilesOnly(false, null, 0f, 0f); tileGrid.setGlobalOpacity(0f) } }
            else -> { wallpaperView.visibility = View.VISIBLE; dim?.visibility = View.VISIBLE; mask?.setActive(false); if (::searchBar.isInitialized) searchBar.isTransparent = false; if (::tileGrid.isInitialized) { tileGrid.setWallpaperTilesOnly(false, null, 0f, 0f); tileGrid.setGlobalOpacity(alpha); if (::searchBar.isInitialized) searchBar.alphaValue = alpha } }
        }
    }

    private fun setupWallpaper() {
        val customUri = Prefs.string(this, Prefs.KEY_WALLPAPER_URI)
        if (customUri != null) runCatching { contentResolver.openInputStream(Uri.parse(customUri))?.use { android.graphics.BitmapFactory.decodeStream(it)?.let { bmp -> wallpaperView.setImageBitmap(bmp); onWallpaperBitmapReady(); return } } }
        runCatching { val drw = android.app.WallpaperManager.getInstance(this).drawable; wallpaperView.setImageDrawable(drw); onWallpaperBitmapReady() }
    }
    private fun onWallpaperBitmapReady() { if (Prefs.string(this, Prefs.KEY_BACKGROUND_MODE, "normal") == "tiles_only") applyBackgroundMode("tiles_only") }
    private var lastUserActivityMs = android.os.SystemClock.elapsedRealtime()
    private fun isAppIdle() = (android.os.SystemClock.elapsedRealtime() - lastUserActivityMs) > 45000L
    private fun updateActivity() { val wasIdle = isAppIdle(); lastUserActivityMs = android.os.SystemClock.elapsedRealtime(); if (wasIdle) { ensureMediaTicker(); ensureSlideshowTicker(); ensureSpecialTileTickers() } }
    override fun onUserInteraction() { super.onUserInteraction(); updateActivity() }
    override fun onResume() {
        super.onResume(); applyUserPreferences(); setupWallpaper()
        // Ordine importante: prima ripuliamo le tile fantasma (notifiche scomparse),
        // poi richiediamo il rebind delle notifiche attive. requestRebind da solo non
        // basta perché non manda update per i package senza notifiche.
        sweepGhostNotifications()
        NotificationListener.requestRebind()
        if (::pager.isInitialized && pager.currentItem == LauncherPagerAdapter.PAGE_START && ::tileGrid.isInitialized) tileGrid.playEntranceAnimation()

        // Check if ShareToStartActivity (or any other external source) added
        // tiles while the launcher was in the background. If so, reload.
        if (::tileGrid.isInitialized) {
            val onDisk = storage.load()
            val newIds = onDisk.map { it.id }.toSet()
            val currentIds = tiles.map { it.id }.toSet()
            if (newIds != currentIds) {
                tiles.clear(); tiles.addAll(onDisk)
                tileGrid.setTiles(tiles) { iconFor(it) }
                hydrateMediaOnAllTiles(); ensureSpecialTileTickers()
            }
        }
    }
    override fun onStart() {
        super.onStart()
        NotificationListener.addListener(this)
        MediaInfoCache.addListener(mediaListener)
        if (::tileGrid.isInitialized) {
            hydrateMediaOnAllTiles()
            ensureMediaTicker()
            ensureSlideshowTicker()
            ensureSpecialTileTickers()
        }
        sweepGhostNotifications()
        NotificationListener.requestRebind()
    }
    override fun onStop() {
        super.onStop()
        stopAllTickers()
        NotificationListener.removeListener(this)
        MediaInfoCache.removeListener(mediaListener)
    }
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
    override fun onBackPressed() {
        val startFrag = runCatching { supportFragmentManager.fragments.firstOrNull { it is com.metrolauncher.activity.StartFragment } as? com.metrolauncher.activity.StartFragment }.getOrNull()
        if (startFrag?.pullDownLayout?.isOpen == true) { startFrag.pullDownLayout.close(); return }
        if (::tileGrid.isInitialized && tileGrid.isFolderExpanded()) { tileGrid.collapseExpandedFolder(); return }
        val drawerFrag = runCatching { supportFragmentManager.fragments.firstOrNull { it is com.metrolauncher.activity.DrawerFragment } as? com.metrolauncher.activity.DrawerFragment }.getOrNull()
        if (drawerFrag?.isJumpListOpen() == true) { drawerFrag.closeJumpList(); return }
        if (editMode) { exitEditMode(); return }
        if (::pager.isInitialized && pager.currentItem != LauncherPagerAdapter.PAGE_START) { pager.currentItem = LauncherPagerAdapter.PAGE_START; return }
    }
    private fun launchVoiceSearch() = runCatching { startActivity(Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { launchGoogleSearch() }
    private fun launchGoogleLens() {
        val intent = Intent("com.google.lens.SEARCH").setPackage("com.google.android.googlequicksearchbox").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                val standalone = packageManager.getLaunchIntentForPackage("com.google.ar.lens")
                if (standalone != null) startActivity(standalone) else throw Exception()
            }.onFailure { openPlayStore("com.google.ar.lens") }
        }
    }
    private fun launchGemini() = runCatching { startActivity(packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { openPlayStore("com.google.android.apps.bard") }
    private fun launchGoogleSearch() = runCatching { startActivity(Intent("com.google.android.googlequicksearchbox.GOOGLE_SEARCH").setPackage("com.google.android.googlequicksearchbox").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun openPlayStore(pkg: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun enterEditMode() { editMode = true; hintArrow.alpha = 0.3f; tileGrid.setEditMode(true) }
    private fun exitEditMode() { editMode = false; hintArrow.alpha = 1f; tileGrid.setEditMode(false) }
}
