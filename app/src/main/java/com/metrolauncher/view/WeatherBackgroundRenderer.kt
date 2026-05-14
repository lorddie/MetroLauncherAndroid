package com.metrolauncher.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import com.metrolauncher.model.WeatherCondition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws an animated background for the weather tile based on [WeatherCondition].
 *
 * Design:
 *  - Background linear gradient (two colors per condition/night).
 *  - Animated primitive elements (sun/clouds/rain/flakes/lightning) drawn on top.
 *  - Animation driven by SystemClock.elapsedRealtime() → absolute time determines 
 *    the phase, so invisible tiles don't consume (just don't call draw).
 *  - Particles (drops, flakes) are DETERMINISTIC pseudo-random: we use a 
 *    hash function on (index, w, h). Thus, between one draw and the next they don't 
 *    "flicker" without reason and no state needs to be saved from frame to frame.
 *
 * The renderer is stateless: call [draw] with current dimensions and condition.
 * TileView keeps a single instance per view and reuses it.
 */
class WeatherBackgroundRenderer {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Cache per il background gradient per evitare di ricreare lo shader ad ogni frame
    private var lastCondition: WeatherCondition? = null
    private var lastNight: Boolean = false
    private var lastH: Float = 0f
    private var cachedShader: LinearGradient? = null

    fun draw(canvas: Canvas, w: Float, h: Float,
             condition: WeatherCondition, isNight: Boolean) {
        drawCondition(canvas, w, h, condition, isNight, alpha = 1f)
    }

    /**
     * Draws a transition between two weather conditions. progress 0 = full "from", 
     * progress 1 = full "to". Linear crossfade on foreground alpha; the 
     * background gradient is instead interpolated between the two palettes in a 
     * continuous way to avoid overlapping of the two gradients.
     */
    fun drawTransition(canvas: Canvas, w: Float, h: Float,
                       from: WeatherCondition, to: WeatherCondition,
                       isNight: Boolean, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        // Interpolated background gradient: a single pass, no double-draw.
        drawInterpolatedGradient(canvas, w, h, from, to, isNight, p)
        // Foregrounds in crossfade: out for from, in for to.
        if (p < 1f) drawConditionForeground(canvas, w, h, from, isNight, alpha = 1f - p)
        if (p > 0f) drawConditionForeground(canvas, w, h, to, isNight, alpha = p)
    }

    /** Draws BG + FG in one go for [draw], with global alpha on FG. */
    private fun drawCondition(canvas: Canvas, w: Float, h: Float,
                               condition: WeatherCondition, isNight: Boolean, alpha: Float) {
        drawGradientBg(canvas, w, h, condition, isNight)
        drawConditionForeground(canvas, w, h, condition, isNight, alpha)
    }

    /** Only the animated elements (sun, clouds, rain, etc.). Background separate. */
    private fun drawConditionForeground(canvas: Canvas, w: Float, h: Float,
                                         condition: WeatherCondition, isNight: Boolean,
                                         alpha: Float) {
        if (alpha <= 0.01f) return
        // Save the alpha state of the main paint and restore it later.
        // All methods below use fgPaint for FGs, so it's the correct filter.
        val saveCount = canvas.saveLayerAlpha(0f, 0f, w, h,
            (alpha * 255).toInt().coerceIn(0, 255))
        when (condition) {
            WeatherCondition.CLEAR         -> if (isNight) drawStars(canvas, w, h)
                                              else drawSun(canvas, w, h)
            WeatherCondition.PARTLY_CLOUDY -> { if (isNight) drawStars(canvas, w, h)
                                                else drawSun(canvas, w, h)
                                                drawClouds(canvas, w, h, density = 0.4f) }
            WeatherCondition.CLOUDY        -> drawClouds(canvas, w, h, density = 1.0f)
            WeatherCondition.FOG           -> drawFog(canvas, w, h)
            WeatherCondition.RAIN          -> { drawClouds(canvas, w, h, density = 0.7f)
                                                drawRain(canvas, w, h) }
            WeatherCondition.THUNDERSTORM  -> { drawClouds(canvas, w, h, density = 1.0f)
                                                drawRain(canvas, w, h)
                                                drawLightning(canvas, w, h) }
            WeatherCondition.SNOW          -> { drawClouds(canvas, w, h, density = 0.7f)
                                                drawSnow(canvas, w, h) }
            WeatherCondition.UNKNOWN       -> { /* nothing */ }
        }
        canvas.restoreToCount(saveCount)
    }

    /** Variant of [drawGradientBg] that interpolates lerp between two palettes to 
     *  keep the bg "soft" during a transition. */
    private fun drawInterpolatedGradient(canvas: Canvas, w: Float, h: Float,
                                          from: WeatherCondition, to: WeatherCondition,
                                          isNight: Boolean, p: Float) {
        val (fTop, fBot) = colorsFor(from, isNight)
        val (tTop, tBot) = colorsFor(to, isNight)
        val top = lerpColor(fTop, tTop, p)
        val bot = lerpColor(fBot, tBot, p)
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h, top, bot, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val rr = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val rg = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val rb = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (rg shl 8) or rb
    }

    // -------- Gradient --------

    private fun drawGradientBg(canvas: Canvas, w: Float, h: Float,
                                c: WeatherCondition, night: Boolean) {
        if (c != lastCondition || night != lastNight || h != lastH || cachedShader == null) {
            val (top, bottom) = colorsFor(c, night)
            cachedShader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
            lastCondition = c
            lastNight = night
            lastH = h
        }
        bgPaint.shader = cachedShader
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null
    }

    /**
     * Palette: top lighter, bottom darker/more saturated.
     * Colors are chosen to keep white text readable in the center.
     */
    private fun colorsFor(c: WeatherCondition, night: Boolean): Pair<Int, Int> {
        if (night) return when (c) {
            WeatherCondition.CLEAR,
            WeatherCondition.PARTLY_CLOUDY -> 0xFF0B1A3F.toInt() to 0xFF000814.toInt()
            WeatherCondition.CLOUDY, WeatherCondition.FOG
                                           -> 0xFF2A3138.toInt() to 0xFF10141A.toInt()
            WeatherCondition.RAIN          -> 0xFF20304A.toInt() to 0xFF0A121F.toInt()
            WeatherCondition.THUNDERSTORM  -> 0xFF2A2440.toInt() to 0xFF0A0614.toInt()
            WeatherCondition.SNOW          -> 0xFF3A4A6A.toInt() to 0xFF1A2236.toInt()
            WeatherCondition.UNKNOWN       -> 0xFF252A30.toInt() to 0xFF101318.toInt()
        }
        return when (c) {
            WeatherCondition.CLEAR         -> 0xFF3AA8E6.toInt() to 0xFF1868B8.toInt()
            WeatherCondition.PARTLY_CLOUDY -> 0xFF77B7E2.toInt() to 0xFF3A7BB0.toInt()
            WeatherCondition.CLOUDY        -> 0xFF8A98A8.toInt() to 0xFF4F5C6C.toInt()
            WeatherCondition.FOG           -> 0xFFB5BCC2.toInt() to 0xFF70787F.toInt()
            WeatherCondition.RAIN          -> 0xFF5B6E82.toInt() to 0xFF2E3D50.toInt()
            WeatherCondition.THUNDERSTORM  -> 0xFF4E4658.toInt() to 0xFF242030.toInt()
            WeatherCondition.SNOW          -> 0xFF93B4CC.toInt() to 0xFF5A7792.toInt()
            WeatherCondition.UNKNOWN       -> 0xFF606870.toInt() to 0xFF353A40.toInt()
        }
    }

    // -------- Sun with rotating rays --------

    private fun drawSun(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.elapsedRealtime() / 1000f
        val cx = w * 0.72f
        val cy = h * 0.28f
        val radius = minOf(w, h) * 0.16f

        // Aura: pulsing semi-transparent soft circle
        val pulse = (sin(t.toDouble() * 1.2).toFloat() + 1f) / 2f  // 0..1
        fgPaint.color = Color.argb((60 + pulse * 40).toInt(), 255, 240, 180)
        canvas.drawCircle(cx, cy, radius * 1.6f, fgPaint)

        // Rays: 12 rays that rotate slowly
        val rayCount = 12
        val rayInner = radius * 1.1f
        val rayOuter = radius * 1.55f
        val angleOff = (t * 8f) % 360f  // 8°/s
        fgPaint.color = 0xE0FFEAA0.toInt()
        fgPaint.strokeWidth = minOf(w, h) * 0.012f
        fgPaint.style = Paint.Style.STROKE
        fgPaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until rayCount) {
            val a = Math.toRadians(((i * 360f / rayCount) + angleOff).toDouble())
            val x1 = cx + cos(a).toFloat() * rayInner
            val y1 = cy + sin(a).toFloat() * rayInner
            val x2 = cx + cos(a).toFloat() * rayOuter
            val y2 = cy + sin(a).toFloat() * rayOuter
            canvas.drawLine(x1, y1, x2, y2, fgPaint)
        }
        fgPaint.style = Paint.Style.FILL

        // Solid body of the sun
        fgPaint.color = 0xFFFFE36B.toInt()
        canvas.drawCircle(cx, cy, radius, fgPaint)
    }

    // -------- Pulsing stars (clear night) --------

    private fun drawStars(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.elapsedRealtime() / 1000f
        val starCount = 18
        fgPaint.color = Color.WHITE
        for (i in 0 until starCount) {
            val fx = rand2d(i, 0) * w
            val fy = rand2d(i, 1) * h * 0.7f  // stars in top 70%
            val baseR = 1.2f + rand2d(i, 2) * 2.3f
            // twinkle: sine wave with pseudo-random phase per star
            val phase = rand2d(i, 3) * (2 * PI).toFloat()
            val tw = (sin(t * 1.5f + phase) + 1f) / 2f
            fgPaint.alpha = (140 + tw * 115).toInt().coerceIn(0, 255)
            canvas.drawCircle(fx, fy, baseR * (0.7f + tw * 0.6f), fgPaint)
        }
        fgPaint.alpha = 255
    }

    // -------- Scrolling clouds --------

    private fun drawClouds(canvas: Canvas, w: Float, h: Float, density: Float) {
        val t = SystemClock.elapsedRealtime() / 1000f
        val cloudCount = (2 + density * 3).toInt().coerceAtLeast(1)
        fgPaint.color = 0xB8FFFFFF.toInt()
        for (i in 0 until cloudCount) {
            val speed = 6f + rand2d(i, 10) * 14f  // px/s
            val startY = h * (0.15f + rand2d(i, 11) * 0.45f)
            val cloudW = w * (0.35f + rand2d(i, 12) * 0.35f)
            val cloudH = cloudW * 0.35f
            // Continuous horizontal wrapping movement
            val range = w + cloudW
            val x = (((t * speed + rand2d(i, 13) * range) % range) - cloudW)
            drawCloud(canvas, x, startY, cloudW, cloudH)
        }
    }

    /** Single cloud: ellipse + 2 round bumps on top. */
    private fun drawCloud(canvas: Canvas, left: Float, top: Float, cw: Float, ch: Float) {
        canvas.drawOval(left, top + ch * 0.25f, left + cw, top + ch, fgPaint)
        canvas.drawCircle(left + cw * 0.30f, top + ch * 0.35f, ch * 0.35f, fgPaint)
        canvas.drawCircle(left + cw * 0.60f, top + ch * 0.20f, ch * 0.45f, fgPaint)
        canvas.drawCircle(left + cw * 0.80f, top + ch * 0.35f, ch * 0.30f, fgPaint)
    }

    // -------- Fog: horizontal sliding bands --------

    private fun drawFog(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.elapsedRealtime() / 1000f
        val bandCount = 5
        for (i in 0 until bandCount) {
            val y = h * (0.15f + i * 0.18f)
            val bandH = h * 0.10f
            val speed = 4f + i * 1.2f
            val phase = (t * speed) % w
            fgPaint.color = Color.argb(60 + i * 10, 255, 255, 255)
            // Draw a wide ellipse shifted cyclically
            canvas.drawOval(-w * 0.2f - phase, y,
                            w * 1.4f - phase, y + bandH, fgPaint)
        }
        fgPaint.alpha = 255
    }

    // -------- Rain --------

    private fun drawRain(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.elapsedRealtime() / 1000f
        val dropCount = 30
        fgPaint.color = 0xCC9DCBFF.toInt()
        fgPaint.strokeWidth = 2f
        fgPaint.style = Paint.Style.STROKE
        fgPaint.strokeCap = Paint.Cap.ROUND
        val speed = 360f  // px/s
        val dropLen = h * 0.08f
        for (i in 0 until dropCount) {
            val x = rand2d(i, 20) * w
            val totalH = h + dropLen
            val y0 = ((t * speed + rand2d(i, 21) * totalH) % totalH) - dropLen
            // Slanted rain: 12° tilt
            val dx = dropLen * 0.2f
            canvas.drawLine(x, y0, x - dx, y0 + dropLen, fgPaint)
        }
        fgPaint.style = Paint.Style.FILL
    }

    // -------- Snow --------

    private fun drawSnow(canvas: Canvas, w: Float, h: Float) {
        val t = SystemClock.elapsedRealtime() / 1000f
        val flakeCount = 22
        fgPaint.color = 0xF0FFFFFF.toInt()
        val speed = 60f  // px/s, slow snow
        val flakeR = minOf(w, h) * 0.012f
        for (i in 0 until flakeCount) {
            val baseX = rand2d(i, 30) * w
            // Horizontal sinusoidal drift for realism
            val drift = sin(t * 0.8f + rand2d(i, 31) * 6.28f) * w * 0.05f
            val totalH = h + flakeR * 2f
            val y0 = ((t * speed * (0.6f + rand2d(i, 32) * 0.8f)
                       + rand2d(i, 33) * totalH) % totalH) - flakeR
            canvas.drawCircle(baseX + drift, y0, flakeR * (0.7f + rand2d(i, 34) * 0.6f), fgPaint)
        }
    }

    // -------- Lightning --------

    private fun drawLightning(canvas: Canvas, w: Float, h: Float) {
        val now = SystemClock.elapsedRealtime()
        // Lightning every 4-7 seconds, duration ~150ms of white flash + polyline.
        val cyclePeriod = 5500L
        val phase = now % cyclePeriod
        if (phase > 180L) return  // most of the time nothing
        val flashAlpha = (1f - phase / 180f)

        // Global flash (semi-transparent white overlay)
        fgPaint.color = Color.argb((flashAlpha * 80).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawRect(0f, 0f, w, h, fgPaint)

        // Zig-zag polyline from top to 2/3 h
        fgPaint.color = Color.argb((flashAlpha * 220).toInt().coerceIn(0, 255), 255, 255, 200)
        fgPaint.style = Paint.Style.STROKE
        fgPaint.strokeWidth = minOf(w, h) * 0.015f
        fgPaint.strokeCap = Paint.Cap.ROUND
        // Stable seed within same cycle → coherent polyline during flash
        val seed = (now / cyclePeriod).toInt()
        path.reset()
        val startX = w * (0.35f + pseudo(seed, 0) * 0.3f)
        path.moveTo(startX, 0f)
        var px = startX
        var py = 0f
        for (i in 0 until 5) {
            px += (pseudo(seed, i * 2 + 1) - 0.5f) * w * 0.28f
            py += h * (0.10f + pseudo(seed, i * 2 + 2) * 0.08f)
            path.lineTo(px, py)
        }
        canvas.drawPath(path, fgPaint)
        fgPaint.style = Paint.Style.FILL
    }

    // -------- Deterministic PRNG (no java.util.Random — faster and reproducible) --------

    /** Hash (index, salt) → float in [0, 1). Stable for same inputs. */
    private fun rand2d(index: Int, salt: Int): Float = pseudo(index, salt)

    private fun pseudo(a: Int, b: Int): Float {
        // Bit-mixing permutation stile xorshift, deterministic.
        var x = (a * 374761393) xor (b * 668265263)
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return ((x and 0x7FFFFFFF).toFloat() / Int.MAX_VALUE.toFloat())
    }
}
