package com.metrolauncher.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.palette.graphics.Palette

object ColorUtils {

    /** Official Windows Phone "Metro" colors, used when the icon yields nothing useful. */
    val METRO_COLORS = intArrayOf(
        0xFF0078D7.toInt(), // blue
        0xFF00B294.toInt(), // teal
        0xFF498205.toInt(), // green
        0xFFE81123.toInt(), // red
        0xFFB4009E.toInt(), // magenta
        0xFF5C2D91.toInt(), // purple
        0xFFCA5010.toInt(), // orange
        0xFF8E562E.toInt(), // brown
        0xFF525E54.toInt(), // gray
        0xFF4C4A48.toInt()  // graphite
    )

    fun randomMetroColor(): Int = METRO_COLORS.random()

    /** Extracts a vivid color from the app icon, fallback to a Metro color. */
    fun dominantColor(icon: Drawable?): Int {
        val bmp = icon?.toBitmapSafe() ?: return randomMetroColor()
        val palette = Palette.from(bmp).generate()
        val swatch = palette.vibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch
        return swatch?.rgb ?: randomMetroColor()
    }

    private fun Drawable.toBitmapSafe(): Bitmap? = runCatching {
        if (this is BitmapDrawable && bitmap != null) return@runCatching bitmap
        val w = intrinsicWidth.coerceAtLeast(1)
        val h = intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bmp
    }.getOrNull()

    /** True if the color is dark (used to decide if overlay text should be white). */
    fun isDark(color: Int): Boolean {
        val darkness = 1 - (
            0.299 * Color.red(color) +
            0.587 * Color.green(color) +
            0.114 * Color.blue(color)
        ) / 255.0
        return darkness >= 0.5
    }
}
