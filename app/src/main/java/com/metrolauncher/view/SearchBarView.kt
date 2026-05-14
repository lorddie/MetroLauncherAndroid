package com.metrolauncher.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Single HORIZONTAL TILE search bar (Option A), in Metro style.
 *
 *   [▒▒▒▒▒▒▒▒▒▒▒▒▒▒ single global-color tile ▒▒▒▒▒▒▒▒▒▒▒▒▒▒]
 *    🔍   Search                              📷   🎙️   ✦
 *
 * All icons are solid white monochrome. The background takes the global color
 * chosen by the user in preferences (KEY_GLOBAL_COLOR_INDEX or CUSTOM).
 *
 * Height: 54dp (25% thinner than the first version which was 72dp).
 *
 * Stable external API for wiring:
 *   barColor, alphaValue, isTransparent, transitionProgress, onActionClicked, Action.
 */
class SearchBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Action { SEARCH, LENS, MIC, GEMINI }

    var onActionClicked: ((Action) -> Unit)? = null

    var barColor: Int = 0xFF0078D7.toInt()
        set(value) { field = value; invalidate() }

    var alphaValue: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var isTransparent: Boolean = false
        set(value) { field = value; invalidate() }

    var transitionProgress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val zoneSearch = RectF()
    private val zoneLens = RectF()
    private val zoneMic = RectF()
    private val zoneGemini = RectF()
    private val searchLensRect = RectF()

    private var pressed: Action? = null
    private var pressScale: Float = 1f
    private var pressAnim: ValueAnimator? = null

    private val density: Float = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Reduced from 16sp → 14sp for consistency with the thinner bar
        hintPaint.textSize = 14f * density
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val w = (r - l).toFloat()
        val h = (b - t).toFloat()

        val hPad = 16f * density
        val topPad = paddingTop.toFloat()
        val botPad = paddingBottom.toFloat().coerceAtLeast(0f)
        val contentH = h - topPad - botPad
        val centerY = topPad + contentH / 2f

        val iconSize = contentH * 0.50f
        val iconGap = 14f * density

        // Search lens on the left (part of the rect)
        val searchIconLeft = hPad
        searchLensRect.set(
            searchIconLeft, centerY - iconSize / 2f,
            searchIconLeft + iconSize, centerY + iconSize / 2f
        )

        // From right: Gemini, Mic, Lens
        val rightX = w - hPad
        zoneGemini.set(rightX - iconSize, centerY - iconSize / 2f, rightX, centerY + iconSize / 2f)
        val micRight = zoneGemini.left - iconGap
        zoneMic.set(micRight - iconSize, centerY - iconSize / 2f, micRight, centerY + iconSize / 2f)
        val lensRight = zoneMic.left - iconGap
        zoneLens.set(lensRight - iconSize, centerY - iconSize / 2f, lensRight, centerY + iconSize / 2f)

        // Search zone: the entire band to the left of the right icon block
        zoneSearch.set(0f, 0f, zoneLens.left - iconGap, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1) Uniform tile background
        if (!isTransparent) {
            val a = (Color.alpha(barColor) * alphaValue).toInt().coerceIn(0, 255)
            bgPaint.color = (barColor and 0x00FFFFFF) or (a shl 24)
            canvas.drawRect(0f, 0f, w, h, bgPaint)
        }

        // 2) Global press scale (on the entire tile, it's a single one)
        val saveCount = canvas.save()
        if (pressScale != 1f) {
            canvas.scale(pressScale, pressScale, w / 2f, h / 2f)
        }

        val white = 0xFFFFFFFF.toInt()
        val slideDist = 12f * density

        fun stagger(offsetStart: Float, offsetEnd: Float): Float {
            return ((transitionProgress - offsetStart) / (offsetEnd - offsetStart)).coerceIn(0f, 1f)
        }

        // 3a) Lens
        val sP = stagger(0.00f, 0.50f)
        canvas.save()
        canvas.translate(0f, -slideDist * (1f - sP))
        drawSearchLensIcon(canvas, searchLensRect, withAlpha(white, (255 * sP).toInt()))
        canvas.restore()

        // 3b) "Search"
        val tP = stagger(0.10f, 0.60f)
        val textX = searchLensRect.right + 12f * density
        val fm = hintPaint.fontMetrics
        val centerY = paddingTop + (h - paddingTop - paddingBottom) / 2f
        val baseline = centerY - (fm.ascent + fm.descent) / 2f
        hintPaint.color = withAlpha(white, (255 * tP * 0.85f).toInt())
        canvas.save()
        canvas.translate(0f, -slideDist * (1f - tP))
        canvas.drawText("Search", textX, baseline, hintPaint)
        canvas.restore()

        // 3c) Lens
        val lP = stagger(0.25f, 0.75f)
        canvas.save()
        canvas.translate(0f, -slideDist * (1f - lP))
        drawLensCameraIcon(canvas, zoneLens, withAlpha(white, (255 * lP).toInt()))
        canvas.restore()

        // 3d) Mic
        val mP = stagger(0.40f, 0.90f)
        canvas.save()
        canvas.translate(0f, -slideDist * (1f - mP))
        drawMicIcon(canvas, zoneMic, withAlpha(white, (255 * mP).toInt()))
        canvas.restore()

        // 3e) Sparkle
        val gP = stagger(0.55f, 1.00f)
        canvas.save()
        canvas.translate(0f, -slideDist * (1f - gP))
        drawSparkleIcon(canvas, zoneGemini, withAlpha(white, (255 * gP).toInt()))
        canvas.restore()

        canvas.restoreToCount(saveCount)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun drawSearchLensIcon(canvas: Canvas, rect: RectF, color: Int) {
        iconStrokePaint.color = color
        val sw = rect.height() * 0.13f
        iconStrokePaint.strokeWidth = sw
        val cx = rect.centerX() - rect.width() * 0.08f
        val cy = rect.centerY() - rect.height() * 0.08f
        val r = rect.width() * 0.30f
        canvas.drawCircle(cx, cy, r, iconStrokePaint)
        val handleStart = r * 0.72f
        val handleEnd = r * 1.30f
        canvas.drawLine(cx + handleStart, cy + handleStart,
                        cx + handleEnd, cy + handleEnd, iconStrokePaint)
    }

    private fun drawLensCameraIcon(canvas: Canvas, zone: RectF, color: Int) {
        iconStrokePaint.color = color
        iconFillPaint.color = color
        val cx = zone.centerX()
        val cy = zone.centerY()
        val s = zone.width() * 0.42f
        val sw = zone.height() * 0.12f
        iconStrokePaint.strokeWidth = sw
        val r = s * 0.30f
        val rect = RectF(cx - s, cy - s, cx + s, cy + s)
        canvas.drawRoundRect(rect, r, r, iconStrokePaint)
        canvas.drawCircle(cx, cy, s * 0.42f, iconFillPaint)
    }

    private fun drawMicIcon(canvas: Canvas, zone: RectF, color: Int) {
        iconFillPaint.color = color
        iconStrokePaint.color = color
        val cx = zone.centerX()
        val cy = zone.centerY()
        val capsuleW = zone.width() * 0.32f
        val capsuleH = zone.height() * 0.50f
        val capsuleTop = cy - capsuleH * 0.6f

        val capsule = RectF(cx - capsuleW / 2f, capsuleTop,
                            cx + capsuleW / 2f, capsuleTop + capsuleH)
        canvas.drawRoundRect(capsule, capsuleW / 2f, capsuleW / 2f, iconFillPaint)

        val sw = zone.height() * 0.10f
        iconStrokePaint.strokeWidth = sw
        val arcSize = capsuleW * 1.45f
        val arcRect = RectF(cx - arcSize / 2f, capsule.bottom - arcSize * 0.55f,
                            cx + arcSize / 2f, capsule.bottom + arcSize * 0.45f)
        canvas.drawArc(arcRect, 20f, 140f, false, iconStrokePaint)
        val stemTop = arcRect.bottom - sw * 0.3f
        val stemBot = zone.bottom - zone.height() * 0.10f
        canvas.drawLine(cx, stemTop, cx, stemBot, iconStrokePaint)
    }

    private fun drawSparkleIcon(canvas: Canvas, zone: RectF, color: Int) {
        iconFillPaint.color = color
        val cx = zone.centerX()
        val cy = zone.centerY()
        val sLong = zone.width() * 0.42f
        val sShort = zone.width() * 0.10f

        val v = Path().apply {
            moveTo(cx, cy - sLong); lineTo(cx + sShort, cy)
            lineTo(cx, cy + sLong); lineTo(cx - sShort, cy); close()
        }
        canvas.drawPath(v, iconFillPaint)

        val hPath = Path().apply {
            moveTo(cx - sLong, cy); lineTo(cx, cy - sShort)
            lineTo(cx + sLong, cy); lineTo(cx, cy + sShort); close()
        }
        canvas.drawPath(hPath, iconFillPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hitTest(x, y)
                if (pressed != null) { animatePressScale(0.96f); return true }
            }
            MotionEvent.ACTION_MOVE -> {
                val current = hitTest(x, y)
                if (current != pressed) { pressed = null; animatePressScale(1f) }
            }
            MotionEvent.ACTION_UP -> {
                val finalAction = pressed
                pressed = null
                animatePressScale(1f)
                if (finalAction != null) {
                    onActionClicked?.invoke(finalAction)
                    performClick()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; animatePressScale(1f) }
        }
        return false
    }

    private fun animatePressScale(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 110L
            interpolator = DecelerateInterpolator()
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun hitTest(x: Float, y: Float): Action? {
        val result = when {
            zoneGemini.contains(x, y) -> Action.GEMINI
            zoneMic.contains(x, y) -> Action.MIC
            zoneLens.contains(x, y) -> Action.LENS
            zoneSearch.contains(x, y) -> Action.SEARCH
            else -> null
        }
        Log.d("MetroSearch", "HitTest at ($x, $y): $result")
        return result
    }
}
