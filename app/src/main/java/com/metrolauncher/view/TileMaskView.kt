package com.metrolauncher.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Overlay drawn ABOVE the wallpaper and BELOW the tile grid.
 *
 * When "wallpaper behind, black between tiles" mode is active, this view is 
 * visible: it draws a black rectangle as large as its entire area, with transparent 
 * "holes" matching the shape and position of the tiles. This way the wallpaper 
 * below is visible ONLY inside the holes (i.e., inside the tiles).
 *
 * Tiles can make themselves transparent normally and the wallpaper will be visible.
 *
 * The View must be kept aligned with the TileGridLayout: the user calls 
 * setTileRects(rects) whenever tiles change layout. Rect coordinates 
 * are RELATIVE to the View itself (after translation).
 */
class TileMaskView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val path = Path()
    private var tileRects: List<RectF> = emptyList()
    private var maskActive: Boolean = false
    private var currentScrollY: Float = 0f

    fun setActive(active: Boolean) {
        if (maskActive == active) return
        maskActive = active
        visibility = if (active) VISIBLE else GONE
        invalidate()
    }

    /** 
     * Sets the current scroll to internally translate the "holes" 
     * while keeping the black mask fixed on the screen.
     */
    fun setScrollY(scrollY: Float) {
        if (currentScrollY == scrollY) return
        currentScrollY = scrollY
        rebuildPath()
        invalidate()
    }

    fun setTileRects(rects: List<RectF>) {
        tileRects = rects
        rebuildPath()
        invalidate()
    }

    private fun rebuildPath() {
        path.reset()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // The outer rectangle is ALWAYS the size of the screen and FIXED.
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        
        // Holes are translated by the current scroll before being added to the path.
        val tempRect = RectF()
        for (r in tileRects) {
            tempRect.set(r)
            // Translate the hole by the same amount tiles are translated in the ScrollView.
            tempRect.offset(0f, currentScrollY)
            
            // We apply 0 radius to keep holes perfectly squared (Metro style)
            path.addRect(tempRect, Path.Direction.CCW)
        }
        path.fillType = Path.FillType.EVEN_ODD
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    override fun onDraw(canvas: Canvas) {
        if (!maskActive) return
        canvas.drawPath(path, fillPaint)
    }
}
