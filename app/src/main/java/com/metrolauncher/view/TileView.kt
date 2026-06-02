package com.metrolauncher.view

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.metrolauncher.R
import com.metrolauncher.model.Tile
import com.metrolauncher.model.TileSize
import com.metrolauncher.util.MediaInfoCache
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single tile view.
 */
class TileView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var tile: Tile? = null
    private var icon: Drawable? = null
    private var mediaInfo: MediaInfoCache.MediaInfo? = null
    private var fontScale: Float = 1f
    private var editModeActive: Boolean = false
    private var wiggleAnimator: ObjectAnimator? = null
    private var globalOpacity: Float = 1f
    private var globalColorOverride: Int? = null
    private var monochromeIcons: Boolean = false
    private var textColor: Int = Color.WHITE

    private var wallpaperTilesOnly: Boolean = false
    private var sharedWallpaper: Bitmap? = null
    private var tileScreenLeft: Float = 0f
    private var tileScreenTop: Float = 0f
    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f

    fun setWallpaperTilesOnly(enabled: Boolean, wallpaper: Bitmap?,
                               screenW: Float, screenH: Float) {
        wallpaperTilesOnly = enabled
        sharedWallpaper = wallpaper
        screenWidth = screenW
        screenHeight = screenH
        invalidate()
    }

    fun setScreenPosition(screenLeft: Float, screenTop: Float) {
        tileScreenLeft = screenLeft
        tileScreenTop = screenTop
    }

    /** Point 4: True if the tile is at least partially visible in the screen viewport. */
    private fun isViewVisibleOnScreen(): Boolean {
        if (screenHeight <= 0) return true // Fallback if not yet initialized
        val top = tileScreenTop
        val bottom = tileScreenTop + height
        return bottom > 0 && top < screenHeight
    }

    private var galleryBitmap: Bitmap? = null
    private var calendarEvents: List<com.metrolauncher.util.CalendarProvider.Event> = emptyList()
    private var weatherSnapshot: com.metrolauncher.util.WeatherProvider.Snapshot? = null
    private val weatherBg = WeatherBackgroundRenderer()

    private var countFromValue: Int = 0
    private var countToValue: Int = 0
    private var countProgress: Float = 1f
    private var countAnimator: ValueAnimator? = null

    private var prevIconLeft: Float = -1f
    private var currentIconLeft: Float = -1f

    private var folderIcons: Map<String, Drawable?> = emptyMap()

    // --- FOLDER 3x3 PUZZLE STATE ---
    // 9 cells indexed 0..8 (row-major: 0 1 2 / 3 4 5 / 6 7 8). One cell is always
    // empty (folderEmptyIdx). The others contain the id of the displayed app.
    // folderQueue is the full list of app ids (even those not visible).
    // folderDisplayOrder remembers the "seniority" of currently visible icons —
    // used to choose which to remove when there are >8 apps in the folder.
    private val folderSlots = arrayOfNulls<String>(9)
    private var folderEmptyIdx: Int = 8
    private var folderQueue: List<String> = emptyList()
    private val folderDisplayOrder = mutableListOf<String>()    // FIFO: head = oldest visible
    private var folderTickCount: Int = 0

    // Slide animation: an adjacent cell to the empty one moves into the empty cell.
    // - folderSlideFromIdx: source cell (contains the icon being moved)
    // - folderSlideToIdx: destination cell (= old folderEmptyIdx)
    // - folderSlideProgress: 0..1
    // If folderSlideExitId != null, it's an "exit" animation — the oldest icon is
    // pushed out of the border and a new one enters in its place.
    private var folderSlideFromIdx: Int = -1
    private var folderSlideToIdx: Int = -1
    private var folderSlideProgress: Float = 0f
    private var folderSlideExitId: String? = null
    private var folderSlideEntryId: String? = null
    private var folderSlideAnimator: android.animation.ValueAnimator? = null

    /** Initializes/updates the puzzle state from the list of apps in the folder. */
    fun setFolderQueue(items: List<String>) {
        if (items == folderQueue) return
        folderQueue = items
        // Reset slots: take the first 8 (or less if folder has <8 items), empty at the center
        // if we have enough, otherwise at the first naturally free slot.
        val visible = items.take(8)
        folderDisplayOrder.clear()
        folderDisplayOrder.addAll(visible)
        for (i in 0..8) folderSlots[i] = null
        // We place visible icons in slots 0..7 leaving 8 empty. Simple and predictable
        // layout for the first frame; the puzzle animation reshuffles later.
        for ((i, id) in visible.withIndex()) folderSlots[i] = id
        folderEmptyIdx = if (visible.size < 9) visible.size else 8
        // Cancel any ongoing animation
        folderSlideAnimator?.cancel()
        folderSlideFromIdx = -1; folderSlideToIdx = -1; folderSlideProgress = 0f
        folderSlideExitId = null; folderSlideEntryId = null
        invalidate()
    }

    /** Puzzle step: called periodically from outside (~every 2.5s).
     *  Chooses a valid move and animates. If there are >8 apps in the folder, every
     *  3 steps it performs a rotation (oldest icon leaves, a new one enters). */
    fun tickFolderPuzzle() {
        if (folderQueue.isEmpty()) return
        if (folderSlideAnimator?.isRunning == true) return  // one animation at a time
        folderTickCount++

        val needsRotation = folderQueue.size > 8 && folderTickCount % 3 == 0
        if (needsRotation) {
            startFolderRotation()
        } else {
            startFolderShuffle()
        }
    }

    /** Animates replacing the oldest icon with the next one in the queue. */
    private fun startFolderRotation() {
        val oldestId = folderDisplayOrder.firstOrNull() ?: return
        val oldestSlot = folderSlots.indexOf(oldestId).takeIf { it >= 0 } ?: return
        // New icon from queue (the first one not already visible)
        val nextId = folderQueue.firstOrNull { it !in folderDisplayOrder } ?: return

        folderSlideExitId = oldestId
        folderSlideEntryId = nextId
        folderSlideFromIdx = oldestSlot
        folderSlideToIdx = oldestSlot  // same cell: "fade + slide-up" animation

        folderSlideAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { folderSlideProgress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    folderSlots[oldestSlot] = nextId
                    folderDisplayOrder.remove(oldestId)
                    folderDisplayOrder.add(nextId)
                    folderSlideExitId = null; folderSlideEntryId = null
                    folderSlideFromIdx = -1; folderSlideToIdx = -1
                    folderSlideProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    /** Animates an internal move: an icon near the empty slot moves into the empty one. */
    private fun startFolderShuffle() {
        val emptyIdx = folderEmptyIdx
        // Orthogonal neighbors in 3x3 grid
        val candidates = mutableListOf<Int>()
        val er = emptyIdx / 3; val ec = emptyIdx % 3
        for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            val nr = er + dr; val nc = ec + dc
            if (nr in 0..2 && nc in 0..2) {
                val ni = nr * 3 + nc
                if (folderSlots[ni] != null) candidates += ni
            }
        }
        if (candidates.isEmpty()) return
        val fromIdx = candidates.random()

        folderSlideFromIdx = fromIdx
        folderSlideToIdx = emptyIdx
        folderSlideExitId = null; folderSlideEntryId = null

        folderSlideAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { folderSlideProgress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    folderSlots[emptyIdx] = folderSlots[fromIdx]
                    folderSlots[fromIdx] = null
                    folderEmptyIdx = fromIdx
                    folderSlideFromIdx = -1; folderSlideToIdx = -1; folderSlideProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    fun setFolderIcons(icons: Map<String, Drawable?>) {
        // If the icon set changes, invalidate the relative monochrome cache
        val oldKeys = folderIcons.keys
        val newKeys = icons.keys
        for (k in oldKeys - newKeys) {
            folderMonoBitmaps.remove(k)
            folderMonoSizes.remove(k)
        }
        // Also for icons that EXIST but have changed (different identity hash): invalidate
        for ((k, drw) in icons) {
            val old = folderIcons[k]
            if (old !== drw) {
                folderMonoBitmaps.remove(k)
                folderMonoSizes.remove(k)
            }
        }
        folderIcons = icons
        invalidate()
    }

    fun setWebIcon(bmp: Bitmap?) {
        this.icon = bmp?.toDrawable(resources)
        invalidate()
    }

    fun setLiveCountAnimated(newCount: Int) {
        val safeNew = newCount.coerceAtLeast(0)
        if (safeNew == countToValue) return
        prevIconLeft = currentIconLeft
        countFromValue = countToValue
        countToValue = safeNew
        countAnimator?.cancel()
        countAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600L
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener {
                countProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private var prevCondition: com.metrolauncher.model.WeatherCondition? = null
    private var transitionStartMs: Long = 0L
    private var displayedTempC: Double = Double.NaN
    private var tempAnimator: ValueAnimator? = null

    private enum class WeatherDisplayMode { ANIMATION, INFO }
    private var weatherDisplayMode = WeatherDisplayMode.ANIMATION

    fun setWeatherSnapshot(snap: com.metrolauncher.util.WeatherProvider.Snapshot?) {
        val oldSnap = weatherSnapshot
        weatherSnapshot = snap
        if (snap == null) { invalidate(); return }
        if (oldSnap != null && oldSnap.condition != snap.condition) {
            prevCondition = oldSnap.condition
            transitionStartMs = android.os.SystemClock.elapsedRealtime()
        } else if (oldSnap == null) {
            prevCondition = null
        }
        val target = snap.tempC
        if (displayedTempC.isNaN() || oldSnap == null) {
            displayedTempC = target
        } else if (kotlin.math.abs(displayedTempC - target) > 0.05) {
            tempAnimator?.cancel()
            tempAnimator = ValueAnimator.ofFloat(displayedTempC.toFloat(), target.toFloat()).apply {
                duration = 800L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    displayedTempC = (it.animatedValue as Float).toDouble()
                    invalidate()
                }
                start()
            }
        }
        invalidate()
    }

    companion object {
        private const val WEATHER_CROSSFADE_MS = 2500L
    }

    fun setGalleryBitmap(bmp: Bitmap?) {
        galleryBitmap = bmp
        artShader = null
        galleryPanProgress = 0f
        galleryPanStart = (Math.random().toFloat() - 0.5f) * 0.3f to (Math.random().toFloat() - 0.5f) * 0.3f
        galleryPanEnd = (Math.random().toFloat() - 0.5f) * 0.3f to (Math.random().toFloat() - 0.5f) * 0.3f
        galleryZoomStart = 1.05f + Math.random().toFloat() * 0.1f
        galleryZoomEnd = 1.15f + Math.random().toFloat() * 0.1f
        invalidate()
    }

    fun setGalleryPanProgress(p: Float) {
        galleryPanProgress = p.coerceIn(0f, 1f)
        invalidate()
    }

    private var galleryPanProgress: Float = 0f
    private var galleryPanStart: Pair<Float, Float> = 0f to 0f
    private var galleryPanEnd: Pair<Float, Float> = 0f to 0f
    private var galleryZoomStart: Float = 1.05f
    private var galleryZoomEnd: Float = 1.15f

    fun setCalendarEvents(events: List<com.metrolauncher.util.CalendarProvider.Event>) {
        calendarEvents = events
        invalidate()
    }

    fun setGlobalOpacity(opacity01: Float) {
        val clamped = opacity01.coerceIn(0f, 1f)
        if (globalOpacity == clamped) return
        globalOpacity = clamped
        invalidate()
    }

    fun setGlobalColorOverride(color: Int?) {
        if (globalColorOverride == color) return
        globalColorOverride = color
        invalidate()
    }

    fun setMonochromeIcons(enabled: Boolean) {
        if (monochromeIcons == enabled) return
        monochromeIcons = enabled
        invalidate()
    }

    fun setTextColor(color: Int) {
        if (textColor == color) return
        textColor = color
        labelPaint.color = textColor
        liveTitlePaint.color = textColor
        liveBodyPaint.color = if (textColor == Color.WHITE) Color.argb(220, 255, 255, 255) else Color.argb(220, 0, 0, 0)
        monochromeFilter = android.graphics.PorterDuffColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
        invalidate()
    }

    private var slideProgress: Float = 1f
    private var slideAnimator: ValueAnimator? = null

    private enum class LiveContentMode { ICON, PREVIEW }
    private var liveContentMode = LiveContentMode.ICON
    private var lastModeChangeMs = 0L
    private var prevMessageIndex: Int = -1

    fun playSlideAnimation() {
        slideAnimator?.cancel()
        slideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { slideProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun playFlipAnimation(onMidWay: (() -> Unit)? = null) {
        cameraDistance = 8000f * resources.displayMetrics.density
        val out = ObjectAnimator.ofFloat(this, "rotationX", 0f, 90f).apply {
            duration = 350
            interpolator = android.view.animation.AccelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onMidWay?.invoke() }
            })
        }
        val into = ObjectAnimator.ofFloat(this, "rotationX", -90f, 0f).apply {
            duration = 350
            interpolator = android.view.animation.DecelerateInterpolator()
        }
        val set = android.animation.AnimatorSet()
        set.playSequentially(out, into)
        set.start()
    }

    fun setFontScale(scale: Float) {
        if (fontScale == scale) return
        fontScale = scale.coerceIn(0.5f, 2f)
        invalidate()
    }

    fun setEditMode(enabled: Boolean) {
        if (editModeActive == enabled) return
        editModeActive = enabled
        if (enabled) {
            wiggleAnimator = ObjectAnimator.ofFloat(this, "rotation", -1.5f, 1.5f).apply {
                duration = 180
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            wiggleAnimator?.cancel()
            wiggleAnimator = null
            rotation = 0f
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wallpaperPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wallpaperShader: BitmapShader? = null
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val liveTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 34f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val liveBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; alpha = 220
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val mediaTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1f, 0xFF000000.toInt())
    }
    private val mediaArtistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEEFFFFFF.toInt(); textSize = 24f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playBadgeFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val weatherIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }

    private val bgRect = RectF()
    private val playPath = Path()
    private val skipPath = Path()
    private var artShader: BitmapShader? = null
    private var artShaderForBitmap: Bitmap? = null

    private val playPauseBadgeBounds = RectF()
    private val prevBadgeBounds = RectF()
    private val nextBadgeBounds = RectF()

    private var monochromeFilter: android.graphics.ColorFilter = android.graphics.PorterDuffColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)

    private var monoBitmap: Bitmap? = null
    private var monoBitmapSourceId: Int = 0
    private var monoBitmapSize: Int = 0
    private var monoBitmapColor: Int = Color.WHITE

    private fun getOrBuildMonoBitmap(drw: Drawable, size: Int): Bitmap? {
        val targetSize = size.coerceAtLeast(1).coerceAtMost(2048)
        val id = System.identityHashCode(drw)
        if (monoBitmap != null && monoBitmapSourceId == id && monoBitmapSize == targetSize && monoBitmapColor == textColor) return monoBitmap

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            drw is android.graphics.drawable.AdaptiveIconDrawable
        ) {
            val mono = drw.monochrome
            if (mono != null) {
                val bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                mono.colorFilter = android.graphics.PorterDuffColorFilter(textColor, android.graphics.PorterDuff.Mode.SRC_IN)
                val oldBounds = mono.bounds
                mono.setBounds(0, 0, targetSize, targetSize)
                mono.draw(c)
                mono.bounds = oldBounds
                mono.colorFilter = null
                val normalized = normalizeLogo(bmp, targetSize)
                monoBitmap = normalized; monoBitmapSourceId = id; monoBitmapSize = targetSize; monoBitmapColor = textColor
                return normalized
            }
        }

        val bmp = runCatching { Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val c = Canvas(bmp)
        val drawableToRender: Drawable = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            drw is android.graphics.drawable.AdaptiveIconDrawable) drw.foreground ?: drw else drw

        val oldBounds = drawableToRender.bounds
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && drw is android.graphics.drawable.AdaptiveIconDrawable) {
            val safeSize = (targetSize * 1.5f).toInt()
            val off = ((safeSize - targetSize) / 2f).toInt()
            drawableToRender.setBounds(-off, -off, targetSize + off, targetSize + off)
        } else {
            drawableToRender.setBounds(0, 0, targetSize, targetSize)
        }
        drawableToRender.draw(c)
        drawableToRender.bounds = oldBounds

        val pixels = IntArray(targetSize * targetSize)
        bmp.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        if (targetSize < 12) {
            val tintPaint = Paint().apply { color = textColor; xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN) }
            c.drawRect(0f, 0f, targetSize.toFloat(), targetSize.toFloat(), tintPaint)
            monoBitmap = bmp; monoBitmapSourceId = id; monoBitmapSize = targetSize; monoBitmapColor = textColor
            return bmp
        }
        
        // ... (rest of the logo extraction logic unchanged but with correct final coloring)
        val patch = (targetSize / 8).coerceAtLeast(2); val margin = (targetSize / 12).coerceAtLeast(1)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var opaqueCount = 0
        val corners = arrayOf(margin to margin, (targetSize - patch - margin) to margin, margin to (targetSize - patch - margin), (targetSize - patch - margin) to (targetSize - patch - margin))
        for ((cx, cy) in corners) {
            for (dy in 0 until patch) {
                for (dx in 0 until patch) {
                    val px = pixels[(cy + dy) * targetSize + (cx + dx)]
                    val a = (px ushr 24) and 0xFF
                    if (a > 150) { rSum += (px ushr 16) and 0xFF; gSum += (px ushr 8) and 0xFF; bSum += px and 0xFF; opaqueCount++ }
                }
            }
        }

        if (opaqueCount < (patch * patch * 4) / 3) {
            val tintPaint = Paint().apply { color = textColor; xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN) }
            c.drawRect(0f, 0f, targetSize.toFloat(), targetSize.toFloat(), tintPaint)
            monoBitmap = bmp; monoBitmapSourceId = id; monoBitmapSize = targetSize; monoBitmapColor = textColor
            return bmp
        }

        val backgroundLum = (0.2126f * (rSum / opaqueCount) + 0.7152f * (gSum / opaqueCount) + 0.0722f * (bSum / opaqueCount)).toInt()
        val diffs = IntArray(pixels.size); var maxDiff = 0
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            if (a < 20) { diffs[i] = -1; continue }
            val lum = (0.2126f * ((pixels[i] ushr 16) and 0xFF) + 0.7152f * ((pixels[i] ushr 8) and 0xFF) + 0.0722f * (pixels[i] and 0xFF)).toInt()
            val px = i % targetSize; val py = i / targetSize
            val dist = sqrt(((px - targetSize/2f) * (px - targetSize/2f) + (py - targetSize/2f) * (py - targetSize/2f)).toDouble()).toFloat()
            val weight = (1.0f - (dist / (targetSize * 0.5f)).coerceIn(0f, 1f)).let { it * it }
            val diff = (Math.abs(lum - backgroundLum) * (0.5f + weight)).toInt()
            diffs[i] = diff; if (diff > maxDiff) maxDiff = diff
        }

        val threshold = (maxDiff * 0.35f).toInt().coerceIn(25, 90)
        val aaBand = (threshold / 2).coerceAtLeast(12)
        val targetColorNoAlpha = textColor and 0x00FFFFFF
        for (i in pixels.indices) {
            val a = (pixels[i] ushr 24) and 0xFF
            if (a < 20 || diffs[i] < 0) { pixels[i] = 0; continue }
            val diff = diffs[i]
            pixels[i] = when {
                diff < threshold - aaBand -> 0
                diff < threshold -> (((a * ((diff - (threshold - aaBand)).toFloat() / aaBand)).toInt().coerceIn(0, 255)) shl 24) or targetColorNoAlpha
                else -> (a shl 24) or targetColorNoAlpha
            }
        }
        bmp.setPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
        val normalized = normalizeLogo(bmp, targetSize)
        monoBitmap = normalized; monoBitmapSourceId = id; monoBitmapSize = targetSize; monoBitmapColor = textColor
        return normalized
    }

    private fun normalizeLogo(src: Bitmap, targetSize: Int): Bitmap {
        val w = src.width; val h = src.height; val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (((pixels[y * w + x] ushr 24) and 0xFF) > 30) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX == -1) return src
        val contentW = (maxX - minX + 1).toFloat(); val contentH = (maxY - minY + 1).toFloat()
        val visualTargetSize = targetSize * 0.72f; val scale = visualTargetSize / Math.max(contentW, contentH)
        val outBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888); val canvas = Canvas(outBmp); val matrix = Matrix()
        matrix.postTranslate(-minX.toFloat(), -minY.toFloat()); matrix.postScale(scale, scale)
        matrix.postTranslate((targetSize - contentW * scale) / 2f, (targetSize - contentH * scale) / 2f)
        canvas.drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return outBmp
    }

    enum class MediaHit { NONE, PLAY_PAUSE, NEXT, PREVIOUS }
    fun mediaHitTest(x: Float, y: Float): MediaHit {
        if (!isMediaMode()) return MediaHit.NONE
        if (playPauseBadgeBounds.contains(x, y)) return MediaHit.PLAY_PAUSE
        if (!nextBadgeBounds.isEmpty && nextBadgeBounds.contains(x, y)) return MediaHit.NEXT
        if (!prevBadgeBounds.isEmpty && prevBadgeBounds.contains(x, y)) return MediaHit.PREVIOUS
        return MediaHit.NONE
    }

    init { isClickable = true; isFocusable = true }

    fun bind(tile: Tile, icon: Drawable?) {
        if (this.icon !== icon) { monoBitmap = null; monoBitmapSourceId = 0 }
        this.tile = tile; this.icon = icon
        prevIconLeft = -1f; currentIconLeft = -1f
        val baseAccent = globalColorOverride ?: tile.accentColor
        val targetAlpha = (globalOpacity * 255f).toInt().coerceIn(0, 255)
        val perTileFactor = if (tile.transparentBackground) 0.35f else 1f
        val finalAlpha = (targetAlpha * perTileFactor).toInt().coerceIn(0, 255)
        bgPaint.color = Color.argb(finalAlpha, Color.red(baseAccent), Color.green(baseAccent), Color.blue(baseAccent))
        labelPaint.color = textColor; liveTitlePaint.color = textColor
        liveBodyPaint.color = if (textColor == Color.WHITE) Color.argb(220, 255, 255, 255) else Color.argb(220, 0, 0, 0)
        badgePaint.color = textColor; badgeTextPaint.color = if (textColor == Color.WHITE) Color.BLACK else Color.WHITE
        if (countToValue != tile.liveCount) { countFromValue = tile.liveCount; countToValue = tile.liveCount; countProgress = 1f; countAnimator?.cancel() }
        invalidate()
    }

    fun setMediaInfo(info: MediaInfoCache.MediaInfo?) {
        mediaInfo = info
        if (info?.albumArt !== artShaderForBitmap) { artShader = null; artShaderForBitmap = info?.albumArt }
        invalidate()
    }

    fun isMediaMode(): Boolean = mediaInfo?.let { !it.title.isNullOrBlank() || it.albumArt != null } ?: false

    override fun onDraw(canvas: Canvas) {
        val t = tile ?: return
        val w = width.toFloat(); val h = height.toFloat(); bgRect.set(0f, 0f, w, h)
        labelPaint.textSize = 28f * fontScale; liveTitlePaint.textSize = 34f * fontScale; liveBodyPaint.textSize = 26f * fontScale

        if (isMediaMode()) {
            drawMediaMode(canvas, t, w, h)
        } else {
            when (t.kind) {
                com.metrolauncher.model.TileKind.CALENDAR -> drawCalendarMode(canvas, t, w, h)
                com.metrolauncher.model.TileKind.CLOCK    -> drawClockMode(canvas, t, w, h)
                com.metrolauncher.model.TileKind.WEATHER  -> drawWeatherMode(canvas, t, w, h)
                com.metrolauncher.model.TileKind.GALLERY  -> drawGalleryMode(canvas, t, w, h)
                com.metrolauncher.model.TileKind.FOLDER   -> drawFolderMode(canvas, t, w, h)
                com.metrolauncher.model.TileKind.WEB_LINK -> drawWebLinkMode(canvas, t, w, h)
                com.metrolauncher.model.TileKind.APP      -> drawStandardMode(canvas, t, w, h)
            }
            if (t.liveCount > 0 && t.kind != com.metrolauncher.model.TileKind.APP) {
                val badgeSize = h * 0.28f
                drawCountBadgeInline(canvas, w - badgeSize - 12f, 12f, badgeSize, t.liveCount)
            }
        }
    }

    private fun drawFolderMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        drawBackground(canvas, w, h)

        val density = resources.displayMetrics.density
        val labelHeight = if (t.showLabel && !t.customLabel.isNullOrBlank()) 22f * density else 0f
        val pad = 10f * density
        val gap = 6f * density
        // 3x3 mosaic — calculate cellSize to fit in available space
        val availW = w - pad * 2
        val availH = h - pad * 2 - labelHeight
        val cellSize = (min((availW - gap * 2) / 3f, (availH - gap * 2) / 3f)).coerceAtLeast(1f)
        val mosaicW = cellSize * 3 + gap * 2
        val mosaicH = cellSize * 3 + gap * 2
        val startX = (w - mosaicW) / 2f
        val startY = (h - mosaicH - labelHeight) / 2f

        // Lazy-init puzzle state if setFolderQueue hasn't been called yet
        if (folderQueue.isEmpty() && t.folderItems.isNotEmpty()) {
            setFolderQueue(t.folderItems.map { it.id })
        }

        // Position of a cell (idx 0..8)
        fun cellLeft(idx: Int) = startX + (idx % 3) * (cellSize + gap)
        fun cellTop(idx: Int) = startY + (idx / 3) * (cellSize + gap)

        // Draw icons in all cells except:
        //  - source cell of movement (folderSlideFromIdx) — icon is in transit
        //  - empty cell (folderEmptyIdx) — nothing to draw
        // The transit icon is drawn above in interpolated position.
        for (i in 0..8) {
            if (i == folderEmptyIdx && folderSlideExitId == null) continue
            if (i == folderSlideFromIdx && folderSlideExitId == null) continue  // will be animated above
            val id = folderSlots[i] ?: continue
            val drw = folderIcons[id] ?: continue
            drawFolderIcon(canvas, drw, id, cellLeft(i), cellTop(i), cellSize)
        }

        // Internal shuffle animation: icon from from → to (empty)
        if (folderSlideFromIdx >= 0 && folderSlideExitId == null) {
            val id = folderSlots[folderSlideFromIdx] ?: return finalizeFolderDraw(canvas, t, h, density, labelHeight, pad)
            val drw = folderIcons[id]
            if (drw != null) {
                val fl = cellLeft(folderSlideFromIdx); val ft = cellTop(folderSlideFromIdx)
                val tl = cellLeft(folderSlideToIdx); val tt = cellTop(folderSlideToIdx)
                val cl = fl + (tl - fl) * folderSlideProgress
                val ct = ft + (tt - ft) * folderSlideProgress
                drawFolderIcon(canvas, drw, id, cl, ct, cellSize)
            }
        }

        // Rotation animation: oldest icon leaves (fade + slide-up), a new one enters
        // from bottom (fade-in) in the same cell.
        if (folderSlideExitId != null) {
            val exitDrw = folderIcons[folderSlideExitId!!]
            val entryDrw = folderIcons[folderSlideEntryId!!]
            val cl = cellLeft(folderSlideFromIdx); val ct = cellTop(folderSlideFromIdx)
            val verticalShift = cellSize * 1.1f
            // Exit: moves up + fade out
            if (exitDrw != null) {
                val exitT = ct - verticalShift * folderSlideProgress
                val alpha = (255 * (1f - folderSlideProgress)).toInt().coerceIn(0, 255)
                folderIconPaint.alpha = alpha
                drawFolderIcon(canvas, exitDrw, folderSlideExitId!!, cl, exitT, cellSize)
                folderIconPaint.alpha = 255
            }
            // Entry: arrives from bottom + fade in
            if (entryDrw != null) {
                val entryT = ct + verticalShift * (1f - folderSlideProgress)
                val alpha = (255 * folderSlideProgress).toInt().coerceIn(0, 255)
                folderIconPaint.alpha = alpha
                drawFolderIcon(canvas, entryDrw, folderSlideEntryId!!, cl, entryT, cellSize)
                folderIconPaint.alpha = 255
            }
        }

        finalizeFolderDraw(canvas, t, h, density, labelHeight, pad)
    }

    // Cache for monochrome bitmaps of mini-icons in folders.
    // Separate from single-entry cache of main tile (monoBitmap) so 8
    // folder icons don't invalidate each other every frame.
    private val folderMonoBitmaps = mutableMapOf<String, Bitmap>()
    private val folderMonoSizes   = mutableMapOf<String, Int>()
    private val folderIconPaint   = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun drawFolderIcon(canvas: Canvas, drw: Drawable, id: String,
                                left: Float, top: Float, size: Float) {
        val sizeInt = size.toInt().coerceAtLeast(1)
        // First try dedicated folder cache (prevents recalc every frame)
        var bmp = folderMonoBitmaps[id]?.takeIf { folderMonoSizes[id] == sizeInt }
        if (bmp == null) {
            // Delegate to main monochrome system (with bg analysis,
            // API-33 native mono, anti-aliasing) — same result as normal drawIcon().
            bmp = getOrBuildMonoBitmap(drw, sizeInt)
            if (bmp != null) {
                // Copy into folder cache so following frames don't recalc
                folderMonoBitmaps[id] = bmp
                folderMonoSizes[id] = sizeInt
            }
        }
        if (bmp != null) {
            canvas.drawBitmap(bmp, left, top, folderIconPaint)
        } else {
            drw.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            drw.draw(canvas)
        }
    }

    private fun finalizeFolderDraw(canvas: Canvas, t: Tile, h: Float,
                                     density: Float, labelHeight: Float, pad: Float) {
        if (labelHeight > 0f) {
            val lbl = t.customLabel ?: ""
            // Use labelPaint directly to have the same font as other tiles
            canvas.drawText(lbl, pad, h - pad / 2f, labelPaint)
        }
    }

    private fun drawWebLinkMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        drawBackground(canvas, w, h)
        val iconSize = min(w, h) * 0.50f
        val drw = icon
        if (drw != null) {
            val left = (w - iconSize) / 2f
            val top = (h - iconSize) / 2f
            drawIcon(canvas, left, top, iconSize)
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
            canvas.drawCircle(w / 2f, h / 2f, iconSize / 2f, p)
            val char = (t.webUrl ?: "W").removePrefix("http://").removePrefix("https://").removePrefix("www.").take(1).uppercase()
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = iconSize * 0.6f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
            canvas.drawText(char, w / 2f, h / 2f - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2f, tp)
        }
        if (t.showLabel) t.customLabel?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, 14f, h - 14f, labelPaint) }
    }

    private fun drawStandardMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        drawBackground(canvas, w, h)
        playPauseBadgeBounds.setEmpty()
        prevBadgeBounds.setEmpty()
        nextBadgeBounds.setEmpty()
        when (t.size) {
            TileSize.SMALL -> drawSmallTile(canvas, w, h)
            TileSize.MEDIUM -> drawMediumTile(canvas, t, w, h, t.liveCount > 0)
            TileSize.WIDE -> drawWideTile(canvas, t, w, h, false, t.liveCount > 0)
            TileSize.LARGE -> drawLargeTile(canvas, t, w, h, false, t.liveCount > 0)
        }
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        val wp = sharedWallpaper
        if (wallpaperTilesOnly && wp != null && screenWidth > 0 && screenHeight > 0) {
            val shader = wallpaperShader ?: BitmapShader(wp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { wallpaperShader = it }
            val scale = max(screenWidth / wp.width, screenHeight / wp.height)
            val m = Matrix().apply { setScale(scale, scale); postTranslate((screenWidth - wp.width * scale) / 2f - tileScreenLeft, (screenHeight - wp.height * scale) / 2f - tileScreenTop) }
            shader.setLocalMatrix(m); wallpaperPaint.shader = shader; canvas.drawRect(bgRect, wallpaperPaint); wallpaperPaint.shader = null
        } else canvas.drawRect(bgRect, bgPaint)
    }

    private fun drawSmallTile(canvas: Canvas, w: Float, h: Float) {
        val iconSize = min(w, h) * 0.55f
        val iconTop = (h - iconSize) / 2f
        val centeredLeft = (w - iconSize) / 2f
        if ((countToValue > 0 || countProgress < 1f) && (countFromValue > 0 || countToValue > 0)) {
            val badgeSize = h * 0.32f
            val gap = 2f
            val minMargin = 4f
            val targetIconLeft = if (countToValue > 0) max(centeredLeft, badgeSize + gap + minMargin) else centeredLeft
            currentIconLeft = targetIconLeft
            val startX = if (prevIconLeft < 0) centeredLeft else prevIconLeft
            val animatedIconLeft = startX + (targetIconLeft - startX) * countProgress
            val badgeLeft = animatedIconLeft - gap - badgeSize
            canvas.withClip(0f, 0f, animatedIconLeft, h) {
                drawCountBadgeInline(canvas, badgeLeft, (h - badgeSize) / 2f, badgeSize, countToValue)
            }
            drawIcon(canvas, animatedIconLeft, iconTop, iconSize)
        } else { 
            currentIconLeft = centeredLeft
            drawIcon(canvas, centeredLeft, iconTop, iconSize) 
        }
    }

    private fun drawMediumTile(canvas: Canvas, t: Tile, w: Float, h: Float, hasCount: Boolean) {
        val preview = currentPreviewText(t)
        val showPreview = !preview.isNullOrBlank() && liveContentMode == LiveContentMode.PREVIEW

        // Odometer-style animation (vertical slide)
        // slideProgress goes from 0 to 1. 0 = old out (top), 1 = new in.
        if (slideProgress < 1f) {
            // During animation we draw both (one enters, one leaves)
            canvas.withTranslation(y = -slideProgress * h) {
                // Quello che esce (se stavamo passando da ICON a PREVIEW, esce ICON)
                val exitMode = if (liveContentMode == LiveContentMode.PREVIEW) LiveContentMode.ICON else LiveContentMode.PREVIEW
                if (exitMode == LiveContentMode.ICON) drawMediumIconState(this, t, w, h, hasCount) else drawMediumPreviewState(this, preview!!, w, h)
            }

            canvas.withTranslation(y = h - slideProgress * h) {
                // Quello che entra
                if (liveContentMode == LiveContentMode.PREVIEW) drawMediumPreviewState(this, preview!!, w, h) else drawMediumIconState(this, t, w, h, hasCount)
            }
        } else {
            // Stable state
            if (showPreview) drawMediumPreviewState(canvas, preview!!, w, h)
            else drawMediumIconState(canvas, t, w, h, hasCount)
        }
    }

    private fun drawMediumIconState(canvas: Canvas, t: Tile, w: Float, h: Float, hasCount: Boolean) {
        val iconSize = min(w, h) * 0.40f; val iconTop = (h - iconSize) / 2f; val centeredLeft = (w - iconSize) / 2f
        if ((countToValue > 0 || countProgress < 1f) && (countFromValue > 0 || countToValue > 0)) {
            val badgeSize = h * 0.28f; val gap = 4f; val minMargin = 6f
            val targetIconLeft = if (countToValue > 0) max(centeredLeft, badgeSize + gap + minMargin) else centeredLeft
            currentIconLeft = targetIconLeft
            val startX = if (prevIconLeft < 0) centeredLeft else prevIconLeft
            val animatedIconLeft = startX + (targetIconLeft - startX) * countProgress
            val badgeLeft = animatedIconLeft - gap - badgeSize
            canvas.save(); canvas.clipRect(0f, 0f, animatedIconLeft, h)
            drawCountBadgeInline(canvas, badgeLeft, (h - badgeSize) / 2f, badgeSize, countToValue)
            canvas.restore(); drawIcon(canvas, animatedIconLeft, iconTop, iconSize)
        } else { currentIconLeft = centeredLeft; drawIcon(canvas, centeredLeft, iconTop, iconSize) }
        
        if (t.showLabel) t.customLabel?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, 14f, h - 14f, labelPaint) }
    }

    private fun drawMediumPreviewState(canvas: Canvas, preview: String, w: Float, h: Float) {
        val density = resources.displayMetrics.density
        val pad = 20f * density
        // Center text a bit more vertically leaving space above and below.
        // We use drawWrappedText directly because the vertical slide (odometer) 
        // is already handled by the translation in drawMediumTile.
        drawWrappedText(canvas, preview, liveTitlePaint, pad, pad + 10f * density, w - pad * 2, maxLines = 4)
    }

    /** Manages the ICON/PREVIEW cycle for medium tiles. */
    fun updateLiveCycle() {
        val t = tile ?: return
        if (t.size != TileSize.MEDIUM) return
        val messages = t.liveMessages
        if (messages.isEmpty() && t.liveBody.isNullOrBlank() && t.liveTitle.isNullOrBlank()) {
            if (liveContentMode != LiveContentMode.ICON) {
                liveContentMode = LiveContentMode.ICON
                invalidate()
            }
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        
        // lastModeChangeMs initialization at first useful tick
        if (lastModeChangeMs == 0L) {
            lastModeChangeMs = now
            return
        }

        val elapsed = now - lastModeChangeMs
        
        if (liveContentMode == LiveContentMode.ICON) {
            if (elapsed >= 10000) { // 10s icon
                liveContentMode = LiveContentMode.PREVIEW
                lastModeChangeMs = now
                playSlideAnimation()
            }
        } else {
            if (elapsed >= 5000) { // 10s for preview
                lastModeChangeMs = now
                if (messages.size > 1) {
                    // Switch to next message
                    t.liveMessageIndex = (t.liveMessageIndex + 1) % messages.size
                    playSlideAnimation()
                } else {
                    // Back to icon
                    liveContentMode = LiveContentMode.ICON
                    playSlideAnimation()
                }
            }
        }
    }

    /** Manages the message cycle for Wide and Large tiles. */
    fun updateWideLargeCycle() {
        val t = tile ?: return
        if (t.size == TileSize.SMALL || t.size == TileSize.MEDIUM) return
        val messages = t.liveMessages
        if (messages.size < 2) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (lastModeChangeMs == 0L) {
            lastModeChangeMs = now
            return
        }
        val elapsed = now - lastModeChangeMs

        if (elapsed >= 6000) { // 6s cycle
            lastModeChangeMs = now
            prevMessageIndex = t.liveMessageIndex
            t.liveMessageIndex = (t.liveMessageIndex + 1) % messages.size
            playSlideAnimation()
        }
    }

    private fun drawWideTile(canvas: Canvas, t: Tile, w: Float, h: Float, @Suppress("UNUSED_PARAMETER") hasLiveContent: Boolean, hasCount: Boolean) {
        val iconSize = h * 0.42f
        val padX = 18f
        val iconTop = (h - iconSize) / 2f
        var xCursor = padX
        if (hasCount && t.liveCount > 0) { 
            drawCountBadgeInline(canvas, xCursor, iconTop, iconSize, t.liveCount)
            xCursor += iconSize + 10f 
        }
        drawIcon(canvas, xCursor, iconTop, iconSize)
        xCursor += iconSize + 10f
        val preview = currentPreviewText(t)
        if (!preview.isNullOrBlank()) {
            drawOdometerWrappedText(canvas, t, liveTitlePaint, xCursor, iconTop + iconSize * 0.15f, w - xCursor - padX, 4)
        }
        if (t.showLabel) {
            t.customLabel?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, padX, h - 16f, labelPaint) }
        }
    }

    private fun drawLargeTile(canvas: Canvas, t: Tile, w: Float, h: Float, @Suppress("UNUSED_PARAMETER") hasLiveContent: Boolean, hasCount: Boolean) {
        val iconSize = h * 0.22f
        val padX = 20f
        val topY = (h / 2f - iconSize) / 2f + (h * 0.05f)
        var xCursor = padX
        if (hasCount && t.liveCount > 0) { 
            drawCountBadgeInline(canvas, xCursor, topY, iconSize, t.liveCount)
            xCursor += iconSize + 12f 
        }
        drawIcon(canvas, xCursor, topY, iconSize)
        val preview = currentPreviewText(t)
        if (!preview.isNullOrBlank()) {
            drawOdometerWrappedText(canvas, t, liveBodyPaint, padX, topY + iconSize + 40f, w - padX * 2, 4)
        }
        if (t.showLabel) {
            t.customLabel?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, padX, h - 18f, labelPaint) }
        }
    }

    private fun currentPreviewText(t: Tile): String? = if (t.liveMessages.isNotEmpty()) t.liveMessages[t.liveMessageIndex.coerceIn(0, t.liveMessages.size - 1)] else t.liveBody?.takeIf { it.isNotBlank() } ?: t.liveTitle?.takeIf { it.isNotBlank() }

    private fun drawOdometerWrappedText(canvas: Canvas, t: Tile, paint: Paint, x: Float, y: Float, maxWidth: Float, maxLines: Int) {
        val messages = t.liveMessages
        val current = if (messages.isNotEmpty()) messages[t.liveMessageIndex.coerceIn(0, messages.size - 1)] else t.liveBody ?: t.liveTitle ?: ""
        
        if (slideProgress >= 1f || prevMessageIndex < 0 || messages.size < 2) {
            drawWrappedText(canvas, current, paint, x, y, maxWidth, maxLines)
            return
        }

        val prev = messages[prevMessageIndex.coerceIn(0, messages.size - 1)]
        val lineHeight = paint.textSize * 1.15f
        val totalH = lineHeight * maxLines
        
        canvas.save()
        // Clip text area to prevent overlap with icon/label
        canvas.clipRect(x, y - paint.textSize, x + maxWidth, y + totalH)
        
        // Old text slides up and fades out
        canvas.save()
        canvas.translate(0f, -slideProgress * totalH)
        paint.alpha = (255 * (1f - slideProgress)).toInt().coerceIn(0, 255)
        drawWrappedText(canvas, prev, paint, x, y, maxWidth, maxLines)
        canvas.restore()
        
        // New text slides in from bottom and fades in
        canvas.save()
        canvas.translate(0f, totalH - slideProgress * totalH)
        paint.alpha = (255 * slideProgress).toInt().coerceIn(0, 255)
        drawWrappedText(canvas, current, paint, x, y, maxWidth, maxLines)
        canvas.restore()
        
        paint.alpha = 255
        canvas.restore()
    }

    private fun drawIcon(canvas: Canvas, left: Float, top: Float, size: Float) {
        val drw = icon ?: return
        if (monochromeIcons) {
            getOrBuildMonoBitmap(drw, size.toInt().coerceAtLeast(1))?.let { canvas.drawBitmap(it, left, top, null) }
            ?: run { drw.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt()); drw.colorFilter = monochromeFilter; drw.draw(canvas); drw.colorFilter = null }
        } else { drw.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt()); drw.draw(canvas) }
    }

    private fun drawCountBadgeInline(canvas: Canvas, left: Float, top: Float, size: Float, count: Int) {
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            color = labelPaint.color
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = size * 0.95f
            setShadowLayer(5f, 0f, 1f, 0xAA000000.toInt()) 
        }
        drawOdometerCount(canvas, count, numberPaint, left + size / 2f, top + size / 2f)
    }

    private fun drawOdometerCount(canvas: Canvas, count: Int, numberPaint: Paint, centerX: Float, centerY: Float) {
        if (count > 99 || countToValue > 99) { 
            val text = if (count > 99) "99+" else count.toString()
            canvas.drawText(text, centerX - numberPaint.measureText(text) / 2f, centerY, numberPaint)
            return 
        }
        val fromStr = countFromValue.toString()
        val toStr = countToValue.toString()
        val maxLen = max(fromStr.length, toStr.length)
        val fromPadded = fromStr.padStart(maxLen, ' ')
        val toPadded = toStr.padStart(maxLen, ' ')
        val digitWidth = numberPaint.measureText("0")
        val startX = centerX - (digitWidth * maxLen) / 2f
        val fm = numberPaint.fontMetrics
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        val rollHeight = (fm.descent - fm.ascent)
        for (i in 0 until maxLen) {
            val fc = fromPadded[i]
            val tc = toPadded[i]
            val xCol = startX + digitWidth * i
            val drawX = xCol + (digitWidth - numberPaint.measureText(tc.toString())) / 2f
            val drawXFrom = xCol + (digitWidth - numberPaint.measureText(fc.toString())) / 2f
            if (fc == tc || countProgress >= 1f) { 
                if (tc != ' ') canvas.drawText(tc.toString(), drawX, baseline, numberPaint) 
            } else { 
                val clipTop = baseline + fm.ascent
                val clipBot = baseline + fm.descent
                canvas.save()
                canvas.clipRect(xCol, clipTop, xCol + digitWidth, clipBot)
                if (fc != ' ') canvas.drawText(fc.toString(), drawXFrom, baseline - countProgress * rollHeight, numberPaint)
                if (tc != ' ') canvas.drawText(tc.toString(), drawX, baseline + (1f - countProgress) * rollHeight, numberPaint)
                canvas.restore()
            }
        }
    }

    private fun drawCalendarMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        canvas.drawRect(bgRect, bgPaint)
        val cal = java.util.Calendar.getInstance(); val dayNum = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val dowAbbrev = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(cal.time).lowercase().trimEnd('.')
        val dowFull = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(cal.time).lowercase()
        val color = labelPaint.color
        when (t.size) {
            TileSize.SMALL -> drawCalendarBlock(canvas, dowAbbrev, dayNum, w / 2f, h / 2f, w * 0.22f, w * 0.55f, color)
            TileSize.MEDIUM -> drawCalendarBlock(canvas, dowFull, dayNum, w / 2f, h / 2f, w * 0.18f, w * 0.55f, color)
            else -> { val padX = 22f; drawCalendarBlock(canvas, dowAbbrev, dayNum, w * 0.80f, h * 0.42f, h * 0.14f, h * 0.55f, color)
                var evY = padX + 26f; val evMaxW = w * 0.58f; val evTitlePaint = textPaint(color, 30f, Paint.Align.LEFT); val evTimePaint = textPaint((color and 0x00FFFFFF) or (0xDD shl 24), 22f, Paint.Align.LEFT, true)
                if (calendarEvents.isEmpty()) canvas.drawText(ellipsize(if (com.metrolauncher.util.CalendarProvider.hasPermission(context)) context.getString(R.string.calendar_no_events) else context.getString(R.string.calendar_grant_access), evTimePaint, evMaxW), padX, evY, evTimePaint)
                else for (ev in calendarEvents.take(if (t.size == TileSize.LARGE) 4 else 2)) { canvas.drawText(ellipsize(ev.title, evTitlePaint, evMaxW), padX, evY, evTitlePaint); evY += evTitlePaint.textSize * 1.05f; canvas.drawText(ellipsize(formatEventTime(ev), evTimePaint, evMaxW), padX, evY, evTimePaint); evY += evTimePaint.textSize * 1.6f; if (evY > h - padX - 28f) break }
                if (t.showLabel) t.customLabel?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, padX, h - 18f, labelPaint) }
            }
        }
    }

    private fun drawCalendarBlock(canvas: Canvas, dowText: String, dayNum: Int, cx: Float, cy: Float, dowSize: Float, dayNumSize: Float, color: Int) {
        val dowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            this.color = color
            textSize = dowSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) 
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            this.color = color
            textSize = dayNumSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) 
        }
        val gap = dowSize * 0.3f
        val totalHeight = dowSize + gap + dayNumSize
        val dowBaseline = cy - totalHeight / 2f + dowSize
        canvas.drawText(dowText, cx, dowBaseline, dowPaint)
        canvas.drawText(dayNum.toString(), cx, dowBaseline + gap + dayNumSize * 0.85f, dayPaint)
    }

    private fun formatEventTime(ev: com.metrolauncher.util.CalendarProvider.Event): String = if (ev.allDay) context.getString(R.string.calendar_all_day) else { val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()); "${fmt.format(java.util.Date(ev.startMs))} – ${fmt.format(java.util.Date(ev.endMs))}" }

    private fun drawClockMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        canvas.drawRect(bgRect, bgPaint)
        val now = java.util.Calendar.getInstance(); val timeText = android.text.format.DateFormat.getTimeFormat(context).format(now.time)
        val alarmMs = com.metrolauncher.util.AlarmProvider.nextAlarmTriggerMs(context)
        val alarmText = alarmMs?.let { com.metrolauncher.util.AlarmProvider.formatNextAlarm(it) }
        val color = labelPaint.color
        when (t.size) {
            TileSize.SMALL -> drawClockBlock(canvas, alarmText, timeText, w / 2f, h / 2f, w * 0.18f, w * 0.38f, color, alarmText != null)
            TileSize.MEDIUM -> drawClockBlock(canvas, alarmText, timeText, w / 2f, h / 2f, w * 0.14f, w * 0.40f, color, alarmText != null)
            else -> { val padX = 22f; val leftW = (w - padX * 3) * 0.48f; val rightX = padX * 2 + leftW; val rightW = w - rightX - padX; val targetSize = h * 0.48f
                val probePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = targetSize; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
                val timeSize = if (probePaint.measureText(timeText) <= rightW * 0.90f) targetSize else targetSize * (rightW * 0.90f / probePaint.measureText(timeText))
                drawClockBlock(canvas, null, timeText, rightX + rightW / 2f, h * 0.50f, 0f, timeSize, color, false)
                val titlePaint = textPaint(color, 28f, Paint.Align.LEFT); val subPaint = textPaint((color and 0x00FFFFFF) or (0xDD shl 24), 22f, Paint.Align.LEFT, true)
                if (alarmText != null) { drawAlarmBellIcon(canvas, padX, h * 0.30f - titlePaint.textSize * 0.85f, titlePaint.textSize, color); canvas.drawText(ellipsize(alarmText, titlePaint, leftW - titlePaint.textSize - 8f), padX + titlePaint.textSize + 8f, h * 0.30f, titlePaint); canvas.drawText(ellipsize(context.getString(R.string.clock_next_alarm), subPaint, leftW), padX, h * 0.30f + titlePaint.textSize * 1.3f, subPaint) }
                else canvas.drawText(ellipsize(context.getString(R.string.clock_no_alarms), subPaint, leftW), padX, h * 0.30f, subPaint)
                if (t.showLabel) t.customLabel?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, padX, h - 18f, labelPaint) }
            }
        }
    }

    private fun drawClockBlock(canvas: Canvas, alarmText: String?, timeText: String, cx: Float, cy: Float, alarmSize: Float, timeSize: Float, color: Int, showBell: Boolean) {
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            this.color = color
            textSize = timeSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) 
        }
        if (!showBell || alarmText == null) { 
            canvas.drawText(timeText, cx, cy - (timePaint.fontMetrics.ascent + timePaint.fontMetrics.descent) / 2f, timePaint)
            return 
        }
        val alarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            this.color = color
            textSize = alarmSize
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) 
        }
        val bellSize = alarmSize * 0.95f
        val groupW = bellSize + 6f + alarmPaint.measureText(alarmText)
        val alarmBaseline = cy - (alarmSize + alarmSize * 0.4f + timeSize) / 2f + alarmSize
        drawAlarmBellIcon(canvas, cx - groupW / 2f, alarmBaseline - bellSize * 0.85f, bellSize, color)
        canvas.drawText(alarmText, cx - groupW / 2f + bellSize + 6f, alarmBaseline, alarmPaint)
        canvas.drawText(timeText, cx, alarmBaseline + alarmSize * 0.4f + timeSize * 0.85f, timePaint)
    }

    private fun drawAlarmBellIcon(canvas: Canvas, left: Float, top: Float, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val cx = left + size / 2f
        val bellTop = top + size * 0.14f
        val bellH = size * 0.62f
        val bellHalfW = size * 0.38f
        canvas.drawCircle(cx, top + size * 0.07f, size * 0.07f, paint)
        val p = Path().apply { 
            moveTo(cx - bellHalfW * 0.20f, bellTop)
            cubicTo(cx - bellHalfW * 1.05f, bellTop + bellH * 0.30f, cx - bellHalfW, bellTop + bellH * 0.85f, cx - bellHalfW, bellTop + bellH)
            lineTo(cx + bellHalfW, bellTop + bellH)
            cubicTo(cx + bellHalfW, bellTop + bellH * 0.85f, cx + bellHalfW * 1.05f, bellTop + bellH * 0.30f, cx + bellHalfW * 0.20f, bellTop)
            close() 
        }
        canvas.drawPath(p, paint)
        canvas.drawRect(cx - bellHalfW * 1.05f, bellTop + bellH + size * 0.02f, cx + bellHalfW * 1.05f, bellTop + bellH + size * 0.07f, paint)
        canvas.drawCircle(cx, bellTop + bellH + size * 0.12f + size * 0.08f, size * 0.08f, paint)
    }

    private fun textPaint(color: Int, size: Float, align: Paint.Align, light: Boolean = false): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; textAlign = align; typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif", Typeface.NORMAL) }

    /** Called by the ticker (1s) to handle the Anim/Info cycle with flip. */
    fun updateWeatherCycle() {
        if (tile?.kind != com.metrolauncher.model.TileKind.WEATHER) return
        val snap = weatherSnapshot ?: return
        if (snap.nextCondition == null) {
            if (weatherDisplayMode != WeatherDisplayMode.ANIMATION) {
                weatherDisplayMode = WeatherDisplayMode.ANIMATION
                invalidate()
            }
            // Occasional log for debugging if data is missing
            val cycle = android.os.SystemClock.elapsedRealtime() % 60000
            if (cycle % 10000 < 1000) android.util.Log.d("TileView", "Weather info mode skipped: nextCondition is null")
            return
        }

        val cycle = android.os.SystemClock.elapsedRealtime() % 60000
        val targetMode = if (cycle > 20000) WeatherDisplayMode.INFO else WeatherDisplayMode.ANIMATION

        if (weatherDisplayMode != targetMode) {
            playFlipAnimation {
                weatherDisplayMode = targetMode
                invalidate()
            }
        }
    }

    private fun drawWeatherMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        val snap = weatherSnapshot
        val condition = snap?.condition ?: com.metrolauncher.model.WeatherCondition.UNKNOWN
        val isNight = snap?.isNight ?: com.metrolauncher.util.WeatherProvider.isNightByLocalClock()

        if (weatherDisplayMode == WeatherDisplayMode.INFO && snap?.nextCondition != null) {
            drawWeatherInfoMode(canvas, snap, w, h)
            return // Skip rest of drawing (temp, location, label) in INFO mode
        } else {
            if (prevCondition != null) { 
                val elapsed = android.os.SystemClock.elapsedRealtime() - transitionStartMs
                if (elapsed < WEATHER_CROSSFADE_MS) {
                    weatherBg.drawTransition(canvas, w, h, prevCondition!!, condition, isNight, elapsed.toFloat() / WEATHER_CROSSFADE_MS)
                } else { 
                    prevCondition = null
                    weatherBg.draw(canvas, w, h, condition, isNight) 
                } 
            } else {
                weatherBg.draw(canvas, w, h, condition, isNight)
            }
            
            // Optimization Point 4: Request continuous redraw only if tile is on screen
            if (isViewVisibleOnScreen()) {
                invalidate()
            }
        }

        val white = Color.WHITE; val whiteDim = Color.argb(220, 255, 255, 255); val tempStr = if (snap == null || displayedTempC.isNaN()) "—°" else "${displayedTempC.toInt()}°"
        when (t.size) {
            TileSize.SMALL -> { val tp = textPaint(white, w * 0.38f, Paint.Align.CENTER, true); val fm = tp.fontMetrics; canvas.drawText(tempStr, w / 2f + tp.measureText("°") / 2f, h / 2f + (fm.descent - fm.ascent) / 2f - fm.descent, tp) }
            TileSize.MEDIUM -> { val tp = textPaint(white, w * 0.32f, Paint.Align.CENTER, true); val fm = tp.fontMetrics; canvas.drawText(tempStr, w / 2f + tp.measureText("°") / 2f, h * 0.48f + (fm.descent - fm.ascent) / 2f - fm.descent, tp); canvas.drawText(ellipsize(snap?.locationName ?: "—", textPaint(whiteDim, 22f, Paint.Align.CENTER, true), w - 20f), w / 2f, h * 0.82f, textPaint(whiteDim, 22f, Paint.Align.CENTER, true)) }
            else -> { val tempSize = h * 0.42f; val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white; textSize = tempSize; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
                val probe = tp.measureText(tempStr); if (probe > (w - 48f) * 0.9f) tp.textSize = tempSize * ((w - 48f) * 0.9f / probe)
                val fm = tp.fontMetrics; canvas.drawText(tempStr, w / 2f + tp.measureText("°") / 2f, h * 0.45f + (fm.descent - fm.ascent) / 2f - fm.descent, tp); canvas.drawText(ellipsize(snap?.locationName ?: "—", textPaint(whiteDim, 24f, Paint.Align.CENTER, true), w - 48f), w / 2f, h * 0.86f, textPaint(whiteDim, 24f, Paint.Align.CENTER, true))
                if (t.showLabel) t.customLabel?.takeIf { it.isNotBlank() }?.let { canvas.drawText(it, 24f, h - 12f, textPaint(whiteDim, 20f, Paint.Align.LEFT)) }
            }
        }
    }

    private fun drawWeatherInfoMode(canvas: Canvas, snap: com.metrolauncher.util.WeatherProvider.Snapshot, w: Float, h: Float) {
        // Standard tile background instead of weather renderer
        drawBackground(canvas, w, h)
        val next = snap.nextCondition ?: return
        val time = snap.nextConditionTime ?: return

        // Much larger icon (55% of tile)
        val iconSize = min(w, h) * 0.55f
        // Vertical centering slightly raised to make room for text below
        val cx = w * 0.5f; val cy = h * 0.42f
        drawWeatherIconPrimitve(canvas, next, cx, cy, iconSize)

        val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
        val conditionLabel = when(next) {
            com.metrolauncher.model.WeatherCondition.CLEAR -> if (snap.isNight) context.getString(R.string.weather_clear_night) else context.getString(R.string.weather_clear_day)
            com.metrolauncher.model.WeatherCondition.PARTLY_CLOUDY -> context.getString(R.string.weather_partly_cloudy)
            com.metrolauncher.model.WeatherCondition.CLOUDY -> context.getString(R.string.weather_cloudy)
            com.metrolauncher.model.WeatherCondition.RAIN -> context.getString(R.string.weather_rain)
            com.metrolauncher.model.WeatherCondition.SNOW -> context.getString(R.string.weather_snow)
            com.metrolauncher.model.WeatherCondition.THUNDERSTORM -> context.getString(R.string.weather_thunderstorm)
            com.metrolauncher.model.WeatherCondition.FOG -> context.getString(R.string.weather_fog)
            else -> ""
        }
        
        val label = context.getString(R.string.weather_at_time, conditionLabel, timeStr)

        // Larger, more readable font
        val tp = textPaint(Color.WHITE, 32f * fontScale, Paint.Align.CENTER, true)
        // Shadow effect to improve visibility against background
        tp.setShadowLayer(4f, 0f, 2f, 0x88000000.toInt())
        canvas.drawText(label, w / 2f, cy + iconSize * 0.75f, tp)
    }

    private fun drawWeatherIconPrimitve(canvas: Canvas, cond: com.metrolauncher.model.WeatherCondition, cx: Float, cy: Float, size: Float) {
        weatherIconPaint.style = Paint.Style.STROKE
        weatherIconPaint.color = Color.WHITE
        val r = size * 0.4f
        when(cond) {
            com.metrolauncher.model.WeatherCondition.CLEAR -> {
                canvas.drawCircle(cx, cy, r, weatherIconPaint)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    canvas.drawLine(cx + (r*1.2f * cos(a)).toFloat(), cy + (r*1.2f * sin(a)).toFloat(),
                        cx + (r*1.6f * cos(a)).toFloat(), cy + (r*1.6f * sin(a)).toFloat(), weatherIconPaint)
                }
            }
            com.metrolauncher.model.WeatherCondition.RAIN -> {
                drawCloudPrimitive(canvas, cx, cy - size*0.1f, size)
                for (i in 0 until 3) {
                    val rx = cx - size*0.2f + i*size*0.2f
                    canvas.drawLine(rx, cy + size*0.2f, rx - size*0.1f, cy + size*0.4f, weatherIconPaint)
                }
            }
            com.metrolauncher.model.WeatherCondition.SNOW -> {
                drawCloudPrimitive(canvas, cx, cy - size*0.1f, size)
                for (i in 0 until 3) {
                    val rx = cx - size*0.2f + i*size*0.2f
                    canvas.drawCircle(rx, cy + size*0.3f, 4f, weatherIconPaint)
                }
            }
            com.metrolauncher.model.WeatherCondition.THUNDERSTORM -> {
                drawCloudPrimitive(canvas, cx, cy - size*0.1f, size)
                val p = Path().apply { moveTo(cx, cy + size*0.1f); lineTo(cx - size*0.1f, cy + size*0.3f); lineTo(cx + size*0.1f, cy + size*0.25f); lineTo(cx, cy + size*0.5f) }
                canvas.drawPath(p, weatherIconPaint)
            }
            else -> drawCloudPrimitive(canvas, cx, cy, size)
        }
    }

    private fun drawCloudPrimitive(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val r = size * 0.25f
        canvas.drawCircle(cx - r*0.8f, cy + r*0.2f, r * 0.7f, weatherIconPaint)
        canvas.drawCircle(cx, cy - r*0.2f, r, weatherIconPaint)
        canvas.drawCircle(cx + r*0.8f, cy + r*0.2f, r * 0.8f, weatherIconPaint)
        canvas.drawLine(cx - r*1.2f, cy + r*0.8f, cx + r*1.2f, cy + r*0.8f, weatherIconPaint)
    }

    private fun drawGalleryMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        galleryBitmap?.let { drawGalleryKenBurns(canvas, it, w, h) } ?: canvas.drawRect(bgRect, bgPaint)
        if (t.showLabel) t.customLabel?.takeIf { it.isNotBlank() }?.let { val sh = h * 0.30f; scrimPaint.shader = LinearGradient(0f, h - sh, 0f, h, 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP); canvas.drawRect(0f, h - sh, w, h, scrimPaint); scrimPaint.shader = null; val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f * fontScale; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()) }; canvas.drawText(it, 18f, h - 16f, p) }
    }

    private fun drawGalleryKenBurns(canvas: Canvas, art: Bitmap, w: Float, h: Float) {
        val zoom = galleryZoomStart + (galleryZoomEnd - galleryZoomStart) * galleryPanProgress; val offX = galleryPanStart.first + (galleryPanEnd.first - galleryPanStart.first) * galleryPanProgress; val offY = galleryPanStart.second + (galleryPanEnd.second - galleryPanStart.second) * galleryPanProgress
        val shader = artShader ?: BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { artShader = it; artShaderForBitmap = art }
        val baseScale = max(w / art.width, h / art.height); val scale = baseScale * zoom; val sw = art.width * scale; val sh = art.height * scale
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate((w - sw) / 2f + offX * sw, (h - sh) / 2f + offY * sh) }); artPaint.shader = shader; canvas.drawRect(bgRect, artPaint); artPaint.shader = null
    }

    private fun drawMediaMode(canvas: Canvas, t: Tile, w: Float, h: Float) {
        val info = mediaInfo!!; info.albumArt?.let { drawAlbumArtCoverCrop(canvas, it, w, h) } ?: canvas.drawRect(bgRect, bgPaint)
        val sh = when (t.size) { TileSize.SMALL -> h * 0.5f; TileSize.MEDIUM -> h * 0.6f; TileSize.WIDE -> h * 0.55f; else -> h * 0.50f }
        scrimPaint.shader = LinearGradient(0f, h - sh, 0f, h, 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP); canvas.drawRect(0f, h - sh, w, h, scrimPaint); scrimPaint.shader = null
        if (t.size != TileSize.SMALL) { val padX = 18f; mediaTitlePaint.textSize = when (t.size) { TileSize.MEDIUM -> 26f; TileSize.WIDE -> 32f; else -> 38f }; mediaArtistPaint.textSize = when (t.size) { TileSize.MEDIUM -> 20f; TileSize.WIDE -> 24f; else -> 28f }
            var y = h - 20f; info.artist?.takeIf { it.isNotBlank() }?.let { canvas.drawText(ellipsize(it, mediaArtistPaint, w - padX * 2), padX, y, mediaArtistPaint); y -= mediaArtistPaint.textSize * 1.25f }
            info.title?.takeIf { it.isNotBlank() }?.let { val ml = if (t.size == TileSize.LARGE) 2 else 1; if (ml == 1) canvas.drawText(ellipsize(it, mediaTitlePaint, w - padX * 2), padX, y, mediaTitlePaint) else drawWrappedText(canvas, it, mediaTitlePaint, padX, y - mediaTitlePaint.textSize * 1.1f, w - padX * 2, ml) }
        }
        val prog = if (info.durationMs > 0) (info.currentPositionMs().toFloat() / info.durationMs).coerceIn(0f, 1f) else 0f
        canvas.drawRect(0f, h - 4f, w, h, progressTrackPaint); canvas.drawRect(0f, h - 4f, w * prog, h, progressFillPaint)
        val ppCx = w - 36f; val ppCy = 36f; drawPlayPauseBadge(canvas, ppCx, ppCy, info.isPlaying)
        if (t.size == TileSize.WIDE || t.size == TileSize.LARGE) { drawSkipBadge(canvas, ppCx - 58f, ppCy, true, nextBadgeBounds); drawSkipBadge(canvas, ppCx - 110f, ppCy, false, prevBadgeBounds) }
        if (t.liveCount > 0) drawNotificationBadge(canvas, w, t.liveCount, 68f)
    }

    private fun drawAlbumArtCoverCrop(canvas: Canvas, art: Bitmap, w: Float, h: Float) {
        val shader = artShader ?: BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { artShader = it; artShaderForBitmap = art }
        val scale = max(w / art.width, h / art.height)
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate((w - art.width * scale) / 2f, (h - art.height * scale) / 2f) }); artPaint.shader = shader; canvas.drawRect(bgRect, artPaint); artPaint.shader = null
    }

    private fun drawPlayPauseBadge(canvas: Canvas, cx: Float, cy: Float, playing: Boolean) {
        val r = 22f; canvas.drawCircle(cx, cy, r, playBadgeBgPaint)
        if (playing) { canvas.drawRect(cx - 9.5f, cy - 9f, cx - 5f, cy + 9f, playBadgeFgPaint); canvas.drawRect(cx + 5f, cy - 9f, cx + 9.5f, cy + 9f, playBadgeFgPaint) }
        else { playPath.rewind(); playPath.moveTo(cx - 6f, cy - 10f); playPath.lineTo(cx + 11f, cy); playPath.lineTo(cx - 6f, cy + 10f); playPath.close(); canvas.drawPath(playPath, playBadgeFgPaint) }
        playPauseBadgeBounds.set(cx - r - 6f, cy - r - 6f, cx + r + 6f, cy + r + 6f)
    }

    private fun drawSkipBadge(canvas: Canvas, cx: Float, cy: Float, forward: Boolean, bounds: RectF) {
        val r = 18f; canvas.drawCircle(cx, cy, r, playBadgeBgPaint); skipPath.rewind(); val s = 12f
        if (forward) { skipPath.moveTo(cx - 10.8f, cy - 6f); skipPath.lineTo(cx - 1.2f, cy); skipPath.lineTo(cx - 10.8f, cy + 6f); skipPath.close(); skipPath.moveTo(cx - 1.2f, cy - 6f); skipPath.lineTo(cx + 8.4f, cy); skipPath.lineTo(cx - 1.2f, cy + 6f); skipPath.close() }
        else { skipPath.moveTo(cx + 10.8f, cy - 6f); skipPath.lineTo(cx + 1.2f, cy); skipPath.lineTo(cx + 10.8f, cy + 6f); skipPath.close(); skipPath.moveTo(cx + 1.2f, cy - 6f); skipPath.lineTo(cx - 8.4f, cy); skipPath.lineTo(cx + 1.2f, cy + 6f); skipPath.close() }
        canvas.drawPath(skipPath, playBadgeFgPaint); bounds.set(cx - r - 6f, cy - r - 6f, cx + r + 6f, cy + r + 6f)
    }

    private fun drawNotificationBadge(canvas: Canvas, w: Float, count: Int, offsetY: Float = 0f) {
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.RIGHT; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = 30f; setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()) }
        canvas.drawText(if (count > 99) "99+" else count.toString(), w - 12f, 36f + offsetY, numberPaint)
    }

    private fun drawWrappedText(canvas: Canvas, text: String, paint: Paint, x: Float, y: Float, maxWidth: Float, maxLines: Int) {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) { 
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate 
            } else { 
                if (current.isNotEmpty()) lines.add(current)
                current = word
                if (lines.size >= maxLines) break 
            } 
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines.add(current)
        if (lines.size > maxLines) lines.subList(maxLines, lines.size).clear()
        if (lines.size == maxLines) { 
            val last = lines[maxLines - 1]
            if (paint.measureText(last) > maxWidth * 0.95f) lines[maxLines - 1] = last.dropLast(1) + "…" 
        }
        var ly = y
        for (line in lines) { 
            canvas.drawText(line, x, ly, paint)
            ly += paint.textSize * 1.15f 
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var lo = 0; var hi = text.length
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (paint.measureText(text.take(mid) + "…") <= maxWidth) lo = mid else hi = mid - 1 }
        return text.take(lo) + "…"
    }

    fun animateTilePress() {
        val a1 = ObjectAnimator.ofFloat(this, "rotationX", 0f, 6f, 0f)
        val a2 = ObjectAnimator.ofFloat(this, "scaleX", 1f, 0.97f, 1f)
        val a3 = ObjectAnimator.ofFloat(this, "scaleY", 1f, 0.97f, 1f)
        listOf(a1, a2, a3).forEach { 
            it.duration = 180
            it.interpolator = AccelerateDecelerateInterpolator()
            it.start() 
        }
    }
}
