package com.metrolauncher.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.metrolauncher.model.Tile
import com.metrolauncher.model.TileKind
import com.metrolauncher.model.TileSize
import com.metrolauncher.util.GridPacker
import com.metrolauncher.util.MediaInfoCache

/**
 * Main tile grid. Handles interactive drag & drop with a "push plan"
 * and entry animations. Optimized for fluidity.
 */
class TileGridLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : ViewGroup(context, attrs, defStyle) {

    var columns: Int = 4; private set
    var gap: Int = 0; set(value) { field = value; requestLayout() }

    private val viewByTileId = mutableMapOf<String, TileView>()
    private val tileByView = mutableMapOf<TileView, Tile>()
    private val iconByView = mutableMapOf<TileView, android.graphics.drawable.Drawable?>()

    var onTileClickListener: ((Tile) -> Unit)? = null
    var onTileLongClickListener: ((Tile, TileView) -> Boolean)? = null
    var onTileMoved: ((Tile) -> Unit)? = null
    var onTileDroppedOnTile: ((dragged: Tile, target: Tile) -> Unit)? = null
    var onEditModeEntered: (() -> Unit)? = null

    private var editMode = false
    private val gridOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val dropTargetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val dropInvalidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF4040 }

    // ---- FOLDER EXPANSION ---------------------------------------------------
    // When a folder is expanded, the rows below the folder slide down to make
    // room for a "slot" where the contained mini-tiles appear. The folder and its
    // mini-tiles remain fully lit; all other tiles are darkened by an animated scrim
    // (see dispatchDraw).
    private var expandedFolderId: String? = null
    private var expandedItemViews: List<TileView> = emptyList()     // temporary created mini-tiles
    private var expansionProgress: Float = 0f                       // 0 = closed, 1 = open
    private var expansionAnimator: android.animation.ValueAnimator? = null
    private var pendingExpandedRows: Int = 0                        // rows occupied by mini-tiles inside the slot
    private val expansionScrimPaint = Paint().apply { color = 0x99000000.toInt() }
    var onFolderTileClick: ((com.metrolauncher.model.Tile) -> Unit)? = null
    /** Invoked after the folder is expanded with (folderTop, totalHeight). The parent 
     *  fragment can use it to scroll the ScrollView so the user sees all content. */
    var onFolderExpanded: ((folderTop: Int, expansionHeight: Int) -> Unit)? = null
    /** Long-press on a mini-tile inside an expanded folder. The handler must: 
     *  remove the tile from the folder, add it to tiles[], then call 
     *  promoteMiniTileToDrag(folderId, miniTile, view, localX, localY)
     *  to continue the drag as a normal tile. */
    var onMiniTileLongPress: ((mini: com.metrolauncher.model.Tile, view: TileView, localX: Float, localY: Float) -> Unit)? = null

    /** True if expanding or expanded. Used by MainActivity to handle the back button. */
    fun isFolderExpanded() = expandedFolderId != null
    fun expandedFolderId(): String? = expandedFolderId

    /** Closes the expanded folder (if open). No-op otherwise. */
    fun collapseExpandedFolder() {
        if (expandedFolderId == null) return
        animateExpansion(open = false)
    }

    /** Opens the folder identified by [folderId] showing its mini-tiles in a
     *  slot below the folder itself. The rows below translate downwards. */
    fun expandFolder(folderId: String, iconProvider: (Tile) -> android.graphics.drawable.Drawable?) {
        // Already open on another folder? Close it first, without animation, 
        // to avoid overlapping states.
        if (expandedFolderId != null && expandedFolderId != folderId) {
            removeExpandedItemViews()
            expandedFolderId = null; expansionProgress = 0f; pendingExpandedRows = 0
        }
        if (expandedFolderId == folderId) return
        val folder = tileByView.values.firstOrNull { it.id == folderId } ?: return
        val items = folder.folderItems
        if (items.isEmpty()) return

        // Pack mini-tiles in a sub-grid with the same columns. Mini-tiles
        // maintain their original size.
        val packed = packItemsAsSubGrid(items, columns)
        pendingExpandedRows = (packed.maxOfOrNull { it.row + it.size.rows } ?: 0)

        // Construct mini-tile views (they are "light" TileViews, not draggable,
        // with click that launches the app directly)
        expandedItemTileByView.clear()
        expandedItemViews = packed.map { mini ->
            val drw = iconProvider(mini)
            val v = TileView(context)
            v.setFontScale(fontScale); v.setGlobalOpacity(globalOpacity); v.setGlobalColorOverride(globalColorOverride)
            v.setMonochromeIcons(monochromeIcons); v.setTextColor(textColor)
            v.setWallpaperTilesOnly(wallpaperTilesOnly, sharedWallpaper, screenWidth, screenHeight)
            v.setEditMode(false); v.bind(mini, drw)
            v.alpha = 0f  // visible only when expansionProgress > 0
            v.setOnClickListener { onFolderTileClick?.invoke(mini); v.animateTilePress() }
            // Long-press on a mini-tile: starts drag-out (removes from folder
            // and promotes to normal tile, then standard drag starts)
            var lx = 0f; var ly = 0f
            v.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) { lx = ev.x; ly = ev.y }; false }
            v.setOnLongClickListener {
                onMiniTileLongPress?.invoke(mini, v, lx, ly)
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                true
            }
            addView(v)
            expandedItemTileByView[v] = mini
            v
        }
        expandedFolderId = folderId
        requestLayout()
        animateExpansion(open = true)
        // Wait for the animation to finish (280ms) before scrolling — if invoked 
        // immediately, tiles haven't moved down yet and calculated target is wrong.
        postDelayed({
            val folderView = tileByView.entries.firstOrNull { it.value.id == folderId }?.key
            if (folderView != null) {
                val cell = (width - gap * (columns + 1)) / columns.coerceAtLeast(1)
                val expansionFinal = pendingExpandedRows * cell + (pendingExpandedRows + 1) * gap
                onFolderExpanded?.invoke(folderView.top, expansionFinal)
            }
        }, 310L)  // 280ms animation + 30ms layout buffer
    }

    /** Fills a sub-grid with [items], respecting their original size, starting 
     *  from row 0. Resulting positions are RELATIVE to the slot (row 0 = right 
     *  under the folder). */
    private fun packItemsAsSubGrid(items: List<Tile>, cols: Int): List<Tile> {
        val placed = mutableListOf<Tile>()
        val occupied = mutableSetOf<Pair<Int, Int>>()
        for (it in items) {
            val mini = it.copy()
            // Force compatible size with current grid
            if (mini.size.cols > cols) mini.size = TileSize.MEDIUM
            outer@ for (r in 0 until 50) for (c in 0..(cols - mini.size.cols)) {
                var ok = true
                for (rr in 0 until mini.size.rows) for (cc in 0 until mini.size.cols) {
                    if ((r + rr to c + cc) in occupied) { ok = false; break }
                }
                if (ok) {
                    mini.row = r; mini.col = c
                    for (rr in 0 until mini.size.rows) for (cc in 0 until mini.size.cols)
                        occupied += (r + rr to c + cc)
                    placed += mini; break@outer
                }
            }
        }
        return placed
    }

    private fun removeExpandedItemViews() {
        for (v in expandedItemViews) removeView(v)
        expandedItemViews = emptyList()
        expandedItemTileByView.clear()
    }

    private fun animateExpansion(open: Boolean) {
        expansionAnimator?.cancel()
        val from = expansionProgress; val to = if (open) 1f else 0f
        expansionAnimator = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { expansionProgress = it.animatedValue as Float; requestLayout(); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!open) {
                        removeExpandedItemViews()
                        expandedFolderId = null; expansionProgress = 0f; pendingExpandedRows = 0
                        requestLayout()
                    }
                }
            })
            start()
        }
    }

    /** Espansione in pixel della fessura, animata. */
    private fun expansionPx(): Int {
        if (pendingExpandedRows == 0) return 0
        val cell = (width - gap * (columns + 1)) / columns.coerceAtLeast(1)
        val total = pendingExpandedRows * cell + (pendingExpandedRows + 1) * gap
        return (total * expansionProgress).toInt()
    }


    private var globalOpacity: Float = 1f
    private var globalColorOverride: Int? = null
    private var monochromeIcons: Boolean = false
    private var textColor: Int = Color.WHITE
    private var fontScale: Float = 1f
    private var wallpaperTilesOnly: Boolean = false
    private var sharedWallpaper: Bitmap? = null
    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f

    init { setWillNotDraw(false) }

    fun setColumns(cols: Int) {
        val safe = if (cols == 6) 6 else 4; if (safe == columns) return; columns = safe
        for (t in tileByView.values.toList()) if (t.size.cols > columns) t.size = TileSize.MEDIUM
        GridPacker.autoLayout(tileByView.values.toList(), columns); requestLayout()
    }
    fun setFontScale(scale: Float) { fontScale = scale; for (v in tileByView.keys) v.setFontScale(scale) }
    fun setGlobalOpacity(opacity01: Float) { globalOpacity = opacity01; for (v in tileByView.keys) v.setGlobalOpacity(opacity01); for ((v, t) in tileByView) v.bind(t, iconByView[v]) }
    fun setGlobalColorOverride(color: Int?) { globalColorOverride = color; for (v in tileByView.keys) v.setGlobalColorOverride(color); for ((v, t) in tileByView) v.bind(t, iconByView[v]) }
    fun setMonochromeIcons(enabled: Boolean) { monochromeIcons = enabled; for (v in tileByView.keys) v.setMonochromeIcons(enabled) }

    /** Advances the puzzle animation of all visible FOLDER tiles. */
    fun tickAllFolderPuzzles() {
        for ((view, tile) in tileByView) {
            if (tile.kind == com.metrolauncher.model.TileKind.FOLDER) view.tickFolderPuzzle()
        }
    }
    /** Updates the puzzle queue of a folder after an item change. */
    fun updateFolderQueue(tileId: String, items: List<String>) {
        viewByTileId[tileId]?.setFolderQueue(items)
    }

    /** Sets the favicon of a WEB_LINK tile via ID — correct and safe. */
    fun setWebIconForTile(tileId: String, bmp: android.graphics.Bitmap) {
        viewByTileId[tileId]?.setWebIcon(bmp)
    }
    fun refreshTiles() { for (v in tileByView.keys) v.invalidate() }
    fun setTextColor(color: Int) { textColor = color; for (v in tileByView.keys) v.setTextColor(color); for ((v, t) in tileByView) v.bind(t, iconByView[v]) }
    fun setWallpaperTilesOnly(enabled: Boolean, wallpaper: android.graphics.Bitmap?, screenW: Float, screenH: Float) {
        wallpaperTilesOnly = enabled; sharedWallpaper = wallpaper; screenWidth = screenW; screenHeight = screenH
        for (v in tileByView.keys) v.setWallpaperTilesOnly(enabled, wallpaper, screenW, screenH); updateTileScreenPositions()
    }

    var onTileRectsChanged: ((List<RectF>) -> Unit)? = null
    private fun currentTileRects(): List<RectF> {
        val out = mutableListOf<RectF>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            out.add(RectF(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat()))
        }
        return out
    }
    private fun updateTileScreenPositions() { val loc = IntArray(2); getLocationOnScreen(loc); val gl = loc[0].toFloat(); val gt = loc[1].toFloat() - scrollOffsetY; for ((v, _) in tileByView) v.setScreenPosition(gl + v.left, gt + v.top) }
    var scrollOffsetY: Int = 0; set(value) { field = value; updateTileScreenPositions(); invalidate() }

    fun setEditMode(enabled: Boolean) { if (editMode == enabled) return; editMode = enabled; for (v in tileByView.keys) v.setEditMode(enabled); invalidate() }
    fun isEditMode(): Boolean = editMode

    fun playEntranceAnimation() {
        val d = resources.displayMetrics.density; val off = 120f * d
        for (i in 0 until childCount) { val v = getChildAt(i) as? TileView ?: continue; val t = tileByView[v] ?: continue
            v.clearAnimation(); v.alpha = 0f; v.translationY = off; v.animate().alpha(1f).translationY(0f).setDuration(350).setStartDelay(t.row * 40L).setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
    }

    fun setTiles(tiles: List<Tile>, iconProvider: (Tile) -> android.graphics.drawable.Drawable?) {
        removeAllViews(); viewByTileId.clear(); tileByView.clear(); iconByView.clear()
        for (t in tiles) {
            val v = addTileInternal(t, iconProvider(t))
            if (t.kind == com.metrolauncher.model.TileKind.FOLDER) {
                v.setFolderIcons(t.folderItems.associate { it.id to iconProvider(it) })
                v.setFolderQueue(t.folderItems.map { it.id })
            }
        }
        requestLayout()
    }
    fun addTile(tile: Tile, icon: android.graphics.drawable.Drawable?) { addTileInternal(tile, icon); requestLayout() }
    fun removeTile(tileId: String) { viewByTileId[tileId]?.let { v -> removeView(v); viewByTileId.remove(tileId); tileByView.remove(v); iconByView.remove(v) }; requestLayout() }
    fun updateTile(tileId: String) { viewByTileId[tileId]?.let { v -> v.bind(tileByView[v] ?: return, iconByView[v]); v.invalidate() } }
    fun updateLiveContent(tileId: String, title: String?, body: String?, count: Int) { viewByTileId[tileId]?.let { v -> tileByView[v]?.apply { liveTitle = title; liveBody = body; liveCount = count }; v.setLiveCountAnimated(count); v.invalidate() } }
    fun updateMediaForPackage(pkg: String, info: MediaInfoCache.MediaInfo?) { for ((v, t) in tileByView) if (t.packageName == pkg) { if (t.mediaDisabled) v.setMediaInfo(null) else v.setMediaInfo(info) } }
    fun tickMediaProgress() { for (v in tileByView.keys) if (v.isMediaMode()) v.invalidate() }
    fun hasAnyMediaTile(): Boolean = tileByView.keys.any { it.isMediaMode() }
    fun flipTile(tileId: String, mid: (() -> Unit)? = null) { viewByTileId[tileId]?.playFlipAnimation(mid) }
    fun advanceSlideshow(tileId: String) { viewByTileId[tileId]?.playSlideAnimation() }
    /** Updates the Live Content cycle (Icon/Preview) for all medium tiles. */
    fun updateLiveCycles() {
        for ((v, t) in tileByView) if (t.size == TileSize.MEDIUM) v.updateLiveCycle()
    }
    fun setCalendarEvents(tileId: String, evs: List<com.metrolauncher.util.CalendarProvider.Event>) { viewByTileId[tileId]?.setCalendarEvents(evs) }
    fun setWeatherSnapshot(tileId: String, snap: com.metrolauncher.util.WeatherProvider.Snapshot?) { viewByTileId[tileId]?.setWeatherSnapshot(snap) }
    fun tickWeatherAnimations() {
        for ((v, t) in tileByView) {
            if (t.kind == com.metrolauncher.model.TileKind.WEATHER) v.invalidate()
        }
    }
    /** Manages the Anim/Info cycle with flip for all weather tiles. */
    fun updateWeatherCycles() {
        for ((v, t) in tileByView) if (t.kind == com.metrolauncher.model.TileKind.WEATHER) v.updateWeatherCycle()
    }
    fun setGalleryBitmap(tileId: String, bmp: android.graphics.Bitmap?) { viewByTileId[tileId]?.setGalleryBitmap(bmp) }
    fun setGalleryPanProgress(tileId: String, p: Float) { viewByTileId[tileId]?.setGalleryPanProgress(p) }
    fun allTileIds(): List<Pair<String, com.metrolauncher.model.TileKind>> = tileByView.values.map { it.id to it.kind }

    private fun addTileInternal(tile: Tile, icon: android.graphics.drawable.Drawable?): TileView {
        val v = TileView(context); v.setFontScale(fontScale); v.setGlobalOpacity(globalOpacity); v.setGlobalColorOverride(globalColorOverride); v.setMonochromeIcons(monochromeIcons); v.setTextColor(textColor); v.setWallpaperTilesOnly(wallpaperTilesOnly, sharedWallpaper, screenWidth, screenHeight); v.setEditMode(editMode); v.bind(tile, icon)
        var lx = 0f; var ly = 0f; v.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) { lx = ev.x; ly = ev.y }; false }
        v.setOnClickListener {
            if (editMode) { onTileClickListener?.invoke(tile); return@setOnClickListener }
            val hit = v.mediaHitTest(lx, ly); if (hit == TileView.MediaHit.NONE) v.animateTilePress()
            when (hit) {
                TileView.MediaHit.PLAY_PAUSE -> com.metrolauncher.service.NotificationListener.sendTransport(tile.packageName, com.metrolauncher.service.NotificationListener.Companion.TransportAction.PLAY_PAUSE)
                TileView.MediaHit.NEXT -> com.metrolauncher.service.NotificationListener.sendTransport(tile.packageName, com.metrolauncher.service.NotificationListener.Companion.TransportAction.NEXT)
                TileView.MediaHit.PREVIOUS -> com.metrolauncher.service.NotificationListener.sendTransport(tile.packageName, com.metrolauncher.service.NotificationListener.Companion.TransportAction.PREVIOUS)
                TileView.MediaHit.NONE -> onTileClickListener?.invoke(tile)
            }
        }
        v.setOnLongClickListener { if (!editMode) { setEditMode(true); v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onEditModeEntered?.invoke(); onTileLongClickListener?.invoke(tile, v); true } else false }
        addView(v); viewByTileId[tile.id] = v; tileByView[v] = tile; iconByView[v] = icon; return v
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val cell = (w - gap * (columns + 1)) / columns.coerceAtLeast(1); var maxB = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? TileView ?: continue
            val t = tileByView[c] ?: expandedItemTileByView[c] ?: continue
            val cw = cell * t.size.cols + gap * (t.size.cols - 1); val ch = cell * t.size.rows + gap * (t.size.rows - 1)
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
            // L'altezza totale viene calcolata solo per le tile "vere" (non mini), perché
            // le mini-tile sono sempre dentro la fessura — il loro spazio è già coperto
            // dall'expansion offset aggiunto alle tile sotto.
            if (c !in expandedItemViews) {
                val b = gap + t.row * (cell + gap) + ch; if (b > maxB) maxB = b
            }
        }
        // Aggiungo l'altezza della fessura
        maxB += expansionPx()
        setMeasuredDimension(w, maxB + gap + paddingBottom + paddingTop)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val cell = (w - gap * (columns + 1)) / columns.coerceAtLeast(1); val cpg = cell + gap
        val expansion = expansionPx()
        // Riga sotto la quale (estremo inferiore della folder + 1) le tile traslano giù
        val splitRow = expandedFolderId?.let { id ->
            tileByView.values.firstOrNull { it.id == id }?.let { it.row + it.size.rows }
        } ?: -1
        // Top base della "fessura" — il gap subito sotto la folder
        val splitTopY = if (splitRow >= 0) gap + splitRow * cpg else 0

        for (i in 0 until childCount) {
            val c = getChildAt(i) as? TileView ?: continue
            if (c in expandedItemViews) continue   // gestite a parte sotto
            val tile = tileByView[c] ?: continue
            if (draggingView == c) continue
            val left = gap + tile.col * cpg
            var top = gap + tile.row * cpg
            // Le tile la cui riga sta sotto il punto di split vengono spinte giù
            if (splitRow >= 0 && tile.row >= splitRow) top += expansion
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }

        // Layout mini-tile dentro la fessura. Si vedono solo se expansion > 0.
        if (expandedItemViews.isNotEmpty()) {
            for (mv in expandedItemViews) {
                val mt = tileByView[mv] ?: mvTile(mv) ?: continue
                val left = gap + mt.col * cpg
                val top = splitTopY + gap + mt.row * cpg
                // Quando expansion < contenuto totale, le mini-tile "emergono" da splitTopY
                // mantenendo la posizione finale e clippandosi grazie a clipChildren del parent.
                mv.layout(left, top, left + mv.measuredWidth, top + mv.measuredHeight)
                // Alpha cresce con il progresso per dare un fade-in
                mv.alpha = expansionProgress
            }
        }

        updateTileScreenPositions(); onTileRectsChanged?.invoke(currentTileRects())
    }

    /** Le mini-tile espanse non sono in tileByView (per evitare di confonderle col
     *  dragging principale). Le track-iamo separatamente in expandedItemTileByView. */
    private val expandedItemTileByView = mutableMapOf<TileView, Tile>()
    private fun mvTile(v: TileView): Tile? = expandedItemTileByView[v]

    private var draggingView: TileView? = null; private var draggingTile: Tile? = null; private var dragOffsetX = 0f; private var dragOffsetY = 0f
    private var hoverRow = -1; private var hoverCol = -1; private var hoverValid = false; private var pendingTile: TileView? = null; private var pdx = 0f; private var pdy = 0f; private var pet = false; private var petx = 0f; private var pety = 0f
    private val ts = (android.view.ViewConfiguration.get(context).scaledTouchSlop * 0.7f).toInt() // Sensibilità aumentata
    var onDragStart: ((TileView) -> Unit)? = null; var onDragEnd: (() -> Unit)? = null; var onEmptyTapInEditMode: (() -> Unit)? = null

    // --- MERGE-INTO-FOLDER (magnetic version) ---
    // Merge into folder activates ONLY if the tile stays still over another for 
    // a brief moment of stability (~150 ms anti-flicker), the target tile becomes 
    // "magnetic": it is NOT pushed away by push-down, it "expands" slightly as visual 
    // feedback, and the drop performs the merge.
    // Below threshold: normal push-down behavior (the target tile escapes to make room).
    // This eliminates the old hover timer which was impossible to center with escaping tiles.
    private var mergeCandidate: TileView? = null     // candidate tile for merge (above threshold now)
    private var mergeReady = false                    // true → drop = merge
    private var pendingMergeCandidate: TileView? = null  // tile above threshold but not yet confirmed
    private val mergeStabilizeRunnable = Runnable {
        // After MERGE_STABILIZE_MS above 80% continuous overlap, promote the candidate
        val c = pendingMergeCandidate ?: return@Runnable
        mergeCandidate = c
        mergeReady = true
        c.animate().scaleX(1.10f).scaleY(1.10f).alpha(0.75f).setDuration(120).start()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
    private val mergeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    companion object {
        private const val MERGE_OVERLAP_THRESHOLD = 0.80f   // 80% area sovrapposta = soglia magnete
        private const val MERGE_STABILIZE_MS = 150L         // tempo sopra soglia per confermare
    }

    private fun cancelMergeHover() {
        mergeHandler.removeCallbacks(mergeStabilizeRunnable)
        pendingMergeCandidate = null
        mergeReady = false
        mergeCandidate?.let { it.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start() }
        mergeCandidate = null
    }

    /** Ratio (0..1) of the area of the dragged tile overlapping the bounding box of [other]. */
    private fun overlapRatio(draggedL: Int, draggedT: Int, draggedR: Int, draggedB: Int, other: TileView): Float {
        // For "other" we use the natural position (without translation), because during 
        // a drag nearby tiles may have temporary translationX/Y from push-down.
        val ot = tileByView[other] ?: return 0f
        val cell = (width - gap * (columns + 1)) / columns.coerceAtLeast(1); val cpg = cell + gap
        val ol = gap + ot.col * cpg
        val otop = gap + ot.row * cpg
        val or_ = ol + cell * ot.size.cols + gap * (ot.size.cols - 1)
        val ob = otop + cell * ot.size.rows + gap * (ot.size.rows - 1)
        val ix = maxOf(0, minOf(draggedR, or_) - maxOf(draggedL, ol))
        val iy = maxOf(0, minOf(draggedB, ob) - maxOf(draggedT, otop))
        val interArea = ix.toFloat() * iy.toFloat()
        // Reference: the area of the dragged tile (usually smaller/equal to target)
        val draggedArea = (draggedR - draggedL).toFloat() * (draggedB - draggedT).toFloat()
        if (draggedArea <= 0f) return 0f
        return (interArea / draggedArea).coerceIn(0f, 1f)
    }


    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // If a folder is expanded: first thing, check if the touch is inside folder or mini.
        // If it's OUTSIDE, close the folder and consume the event (don't pass tap to other Views).
        if (expandedFolderId != null && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val folderId = expandedFolderId!!
            val folderView = tileByView.entries.firstOrNull { it.value.id == folderId }?.key
            val keepViews = mutableListOf<View>()
            folderView?.let { keepViews += it }
            keepViews += expandedItemViews
            val insideKeep = keepViews.any { v ->
                ev.x >= v.left && ev.x <= v.right && ev.y >= v.top && ev.y <= v.bottom
            }
            if (!insideKeep) {
                collapseExpandedFolder()
                return true
            }
            // Inside: let the touch reach the mini-tile (for app click)
        }

        if (!editMode) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { findTileAt(ev.x, ev.y)?.let { pendingTile = it; pdx = ev.x; pdy = ev.y; return super.dispatchTouchEvent(ev) } ?: run { pet = true; petx = ev.x; pety = ev.y; return true } }
            MotionEvent.ACTION_MOVE -> {
                if (draggingView != null) { updateDrag(ev.x, ev.y); return true }
                if (pet) { if ((ev.x - petx) * (ev.x - petx) + (ev.y - pety) * (ev.y - pety) > ts * ts) pet = false; return true }
                pendingTile?.let { val dx = ev.x - pdx; val dy = ev.y - pdy; if (dx * dx + dy * dy > ts * ts) { val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle(); startDrag(it, ev.x, ev.y); pendingTile = null; return true } }
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (draggingView != null) {
                    if (mergeReady && mergeCandidate != null && mergeCandidate !== draggingView) {
                        // Hover completato → fondi nella folder
                        val target = mergeCandidate!!
                        cancelMergeHover()
                        onTileDroppedOnTile?.invoke(draggingTile!!, tileByView[target]!!)
                        resetDraggingView(draggingView!!)
                        pendingTile = null
                        return true
                    }
                    // Drop normale: solo riordinamento, no fusione
                    cancelMergeHover()
                    endDrag(true); pendingTile = null; return true
                }
                if (pet) { pet = false; onEmptyTapInEditMode?.invoke(); return true }; pendingTile = null; return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_CANCEL -> { cancelMergeHover(); draggingView?.let { endDrag(false) }; pendingTile = null; pet = false; return super.dispatchTouchEvent(ev) }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun findTileAt(x: Float, y: Float): TileView? { for (i in childCount - 1 downTo 0) { val c = getChildAt(i) as? TileView ?: continue; if (x >= c.left && x <= c.right && y >= c.top && y <= c.bottom) return c }; return null }
    /** Programmatically starts a drag on a specific tile. Used when a folder's
     *  mini-tile is promoted to a normal tile: the user's finger is already on
     *  it and we want the drag to continue without release. */
    fun startDragOnTile(tileId: String, localX: Float, localY: Float) {
        val v = viewByTileId[tileId] ?: return
        val tile = tileByView[v] ?: return
        // Force edit mode so the whole drag system works
        if (!editMode) setEditMode(true)
        // Position offset as if the finger just touched (localX, localY)
        // relative to the view's bottom-left point
        startDrag(v, v.left + localX, v.top + localY)
    }

    private fun startDrag(view: TileView, x: Float, y: Float) { draggingView = view; draggingTile = tileByView[view]; dragOffsetX = x - view.left; dragOffsetY = y - view.top; view.elevation = 30f; view.alpha = 0.85f; view.scaleX = 1.05f; view.scaleY = 1.05f; view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); onDragStart?.invoke(view) }
    private var cpp: GridPacker.MoveResult? = null
    private fun updateDrag(x: Float, y: Float) {
        val v = draggingView ?: return; val t = draggingTile ?: return
        val nl = (x - dragOffsetX).toInt(); val nt = (y - dragOffsetY).toInt()
        v.layout(nl, nt, nl + v.width, nt + v.height)
        val nr = nl + v.width; val nb = nt + v.height

        // Trova la tile con sovrapposizione massima sopra soglia (escludendo se stessa).
        // Iteriamo solo le tile "vere" (tileByView), non le mini espanse né i candidati merge.
        var bestOverlap = 0f; var bestTarget: TileView? = null
        for ((view, tile) in tileByView) {
            if (view === v) continue
            if (tile.id == t.id) continue
            val ov = overlapRatio(nl, nt, nr, nb, view)
            if (ov > bestOverlap) { bestOverlap = ov; bestTarget = view }
        }

        val crossed = bestOverlap >= MERGE_OVERLAP_THRESHOLD && bestTarget != null
        if (crossed) {
            // Sopra soglia: pretendiamo di voler fondere su questa tile target
            if (pendingMergeCandidate !== bestTarget && mergeCandidate !== bestTarget) {
                // Nuovo candidato: cancella stato precedente, riparti timer di stabilizzazione
                cancelMergeHover()
                pendingMergeCandidate = bestTarget
                mergeHandler.postDelayed(mergeStabilizeRunnable, MERGE_STABILIZE_MS)
            }
            // Se già abbiamo confermato il merge su questo stesso target (mergeReady),
            // teniamo TUTTE le tile ferme (no push-down) per dare effetto magnetico.
            // Cancello eventuali animazioni di push-down lasciate da frame precedenti.
            if (mergeReady) {
                for ((ov_view, _) in tileByView) if (ov_view !== v && (ov_view.translationX != 0f || ov_view.translationY != 0f))
                    ov_view.animate().translationX(0f).translationY(0f).setDuration(120).start()
                cpp = null; hoverValid = false
                invalidate(); return
            }
            // Pending ma non ancora confermato: NON facciamo push-down sotto la tile candidata.
            // Calcoliamo comunque pushDown per le altre, ma escludiamo bestTarget dal piano.
        } else {
            // Sotto soglia: nessun candidato attivo. Annulla eventuale merge in attesa.
            if (pendingMergeCandidate != null || mergeCandidate != null) cancelMergeHover()
        }

        // Push-down preview per riordinamento normale
        val cell = (width - gap * (columns + 1)) / columns.coerceAtLeast(1); val cpg = cell + gap
        hoverCol = ((nl - gap + cell / 2) / cpg).coerceIn(0, columns - t.size.cols); hoverRow = ((nt - gap + cell / 2) / cpg).coerceAtLeast(0)
        cpp = GridPacker.pushDown(tileByView.values.toList(), t, hoverRow, hoverCol, columns); hoverValid = cpp != null
        if (cpp != null) {
            for ((id, pos) in cpp!!.shifted) {
                val other = viewByTileId[id] ?: continue
                val ot = tileByView[other] ?: continue
                // Se questa tile è il pending o confirmed merge candidate: NON spostarla.
                if (other === pendingMergeCandidate || other === mergeCandidate) {
                    if (other.translationX != 0f || other.translationY != 0f)
                        other.animate().translationX(0f).translationY(0f).setDuration(120).start()
                    continue
                }
                val dl = gap + pos.second * cpg; val dt = gap + pos.first * cpg
                other.animate().translationX((dl - (gap + ot.col * cpg)).toFloat())
                    .translationY((dt - (gap + ot.row * cpg)).toFloat()).setDuration(120).start()
            }
            for ((ov_view, ot) in tileByView)
                if (ot.id != t.id && ot.id !in cpp!!.shifted.keys
                    && ov_view !== pendingMergeCandidate && ov_view !== mergeCandidate
                    && (ov_view.translationX != 0f || ov_view.translationY != 0f))
                    ov_view.animate().translationX(0f).translationY(0f).setDuration(120).start()
        }
        invalidate()
    }
    private fun endDrag(committed: Boolean) {
        val v = draggingView ?: return; val t = draggingTile ?: return; val cell = (width - gap * (columns + 1)) / columns.coerceAtLeast(1); val cpg = cell + gap
        val al: Int; val at: Int
        if (committed && cpp != null) { t.row = cpp!!.movingTile.row; t.col = cpp!!.movingTile.col; for ((id, pos) in cpp!!.shifted) tileByView.values.firstOrNull { it.id == id }?.let { it.row = pos.first; it.col = pos.second }; for (ov in tileByView.keys) if (ov !== v) { ov.translationX = 0f; ov.translationY = 0f }; al = gap + t.col * cpg; at = gap + t.row * cpg; onTileMoved?.invoke(t); requestLayout() }
        else { al = gap + t.col * cpg; at = gap + t.row * cpg; for (ov in tileByView.keys) if (ov !== v) ov.animate().translationX(0f).translationY(0f).setDuration(150).start() }
        val sl = v.left; val st = v.top; ValueAnimator.ofFloat(0f, 1f).apply { duration = 180; interpolator = DecelerateInterpolator(); addUpdateListener { val f = it.animatedValue as Float; v.layout((sl + (al - sl) * f).toInt(), (st + (at - st) * f).toInt(), (sl + (al - sl) * f).toInt() + v.width, (st + (at - st) * f).toInt() + v.height) }; start() }
        resetDraggingView(v)
    }
    private fun resetDraggingView(v: TileView) { v.elevation = 0f; v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; draggingView = null; draggingTile = null; hoverRow = -1; hoverCol = -1; hoverValid = false; cpp = null; invalidate(); onDragEnd?.invoke() }
    private val expansionScrimPath = android.graphics.Path()
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        // Disegna tutte le View prima
        super.dispatchDraw(canvas)

        // Edit mode overlay (griglia + hover)
        if (editMode) {
            val cell = (width - gap * (columns + 1)) / columns.coerceAtLeast(1)
            val tr = (tileByView.values.maxOfOrNull { it.row + it.size.rows } ?: 0) + 2
            for (r in 0 until tr) for (c in 0 until columns)
                canvas.drawRect((gap + c * (cell + gap)).toFloat(), (gap + r * (cell + gap)).toFloat(),
                    (gap + c * (cell + gap) + cell).toFloat(), (gap + r * (cell + gap) + cell).toFloat(), gridOverlayPaint)
            if (draggingView != null && hoverRow >= 0 && hoverCol >= 0) {
                val t = draggingTile!!
                val l = gap + hoverCol * (cell + gap); val tp = gap + hoverRow * (cell + gap)
                canvas.drawRect(l.toFloat(), tp.toFloat(),
                    (l + cell * t.size.cols + gap * (t.size.cols - 1)).toFloat(),
                    (tp + cell * t.size.rows + gap * (t.size.rows - 1)).toFloat(),
                    if (hoverValid) dropTargetPaint else dropInvalidPaint)
            }
        }

        // Scrim selettivo durante l'espansione folder: copre tutto tranne folder + mini-tile
        if (expansionProgress > 0f) {
            val folderId = expandedFolderId
            val folderView = folderId?.let { id -> tileByView.entries.firstOrNull { it.value.id == id }?.key }
            expansionScrimPath.reset()
            // Rettangolo esterno (CW): tutto lo scrim
            expansionScrimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), android.graphics.Path.Direction.CW)
            // Sub-paths CCW: i "buchi" per le View da preservare
            val keep = mutableListOf<View>()
            folderView?.let { keep += it }
            keep += expandedItemViews
            for (v in keep) {
                expansionScrimPath.addRect(v.left.toFloat(), v.top.toFloat(),
                    v.right.toFloat(), v.bottom.toFloat(), android.graphics.Path.Direction.CCW)
            }
            expansionScrimPath.fillType = android.graphics.Path.FillType.EVEN_ODD
            expansionScrimPaint.alpha = (160 * expansionProgress).toInt().coerceIn(0, 255)
            canvas.drawPath(expansionScrimPath, expansionScrimPaint)
        }
    }
}
