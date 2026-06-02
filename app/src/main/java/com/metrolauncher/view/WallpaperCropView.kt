package com.metrolauncher.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

import androidx.core.graphics.toColorInt
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Custom view for cropping/panning/zooming a wallpaper.
 * Shows a frame representing the screen aspect ratio.
 */
class WallpaperCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val savedMatrix = Matrix()
    
    private var mode = MODE_NONE
    private val startPoint = PointF()
    private val midPoint = PointF()
    private var oldDist = 1f
    private val frameMargin = 60f

    private fun getFrameRect(): RectF {
        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        val frameW = vWidth - frameMargin * 2
        val frameH = vHeight - frameMargin * 2
        return RectF(
            (vWidth - frameW) / 2f,
            (vHeight - frameH) / 2f,
            (vWidth + frameW) / 2f,
            (vHeight + frameH) / 2f
        )
    }

    private fun applyConstraints(fluid: Boolean) {
        val bmp = bitmap ?: return
        val frameRect = getFrameRect()

        // 1. Ensure minimum scale (No black borders)
        val matrixValues = FloatArray(9)
        imageMatrix.getValues(matrixValues)
        val currentScale = matrixValues[Matrix.MSCALE_X]
        val minScale = max(frameRect.width() / bmp.width, frameRect.height() / bmp.height)

        if (currentScale < minScale) {
            val rescale = minScale / currentScale
            // If fluid, we zoom in slowly, otherwise we snap
            val factor = if (fluid) (1f + (rescale - 1f) * 0.2f) else rescale
            imageMatrix.postScale(factor, factor, frameRect.centerX(), frameRect.centerY())
            if (fluid) postInvalidateOnAnimation()
        }

        // 2. Position constraints and magnetism
        val imageRect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(imageRect)

        var dX = 0f
        var dY = 0f

        // a) Black border prevention (Strict/High priority)
        if (imageRect.left > frameRect.left) dX = frameRect.left - imageRect.left
        else if (imageRect.right < frameRect.right) dX = frameRect.right - imageRect.right

        if (imageRect.top > frameRect.top) dY = frameRect.top - imageRect.top
        else if (imageRect.bottom < frameRect.bottom) dY = frameRect.bottom - imageRect.bottom

        // b) Magnetic edges (50px threshold, only if not already correcting a border)
        val threshold = 50f
        if (dX == 0f) {
            if (Math.abs(imageRect.left - frameRect.left) < threshold) dX = frameRect.left - imageRect.left
            else if (Math.abs(imageRect.right - frameRect.right) < threshold) dX = frameRect.right - imageRect.right
        }
        if (dY == 0f) {
            if (Math.abs(imageRect.top - frameRect.top) < threshold) dY = frameRect.top - imageRect.top
            else if (Math.abs(imageRect.bottom - frameRect.bottom) < threshold) dY = frameRect.bottom - imageRect.bottom
        }

        if (dX != 0f || dY != 0f) {
            val easing = if (fluid) 0.20f else 1f
            imageMatrix.postTranslate(dX * easing, dY * easing)
            if (fluid && (Math.abs(dX) > 0.5f || Math.abs(dY) > 0.5f)) {
                postInvalidateOnAnimation()
            }
        }
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#88000000".toColorInt()
        style = Paint.Style.FILL
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val nextMatrix = Matrix(imageMatrix)
            nextMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            
            // Strict constraint check for scale
            val values = FloatArray(9)
            nextMatrix.getValues(values)
            val scale = values[Matrix.MSCALE_X]
            val frame = getFrameRect()
            val bmp = bitmap ?: return true
            val minScale = max(frame.width() / bmp.width, frame.height() / bmp.height)
            
            if (scale >= minScale) {
                imageMatrix.set(nextMatrix)
                // Also apply strict position clamp to prevent black borders during zoom
                applyConstraints(false)
            }
            
            invalidate()
            return true
        }
    })

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        // Initial fit: center crop
        post {
            if (width <= 0 || height <= 0) return@post
            val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            imageMatrix.setScale(scale, scale)
            val dx = (width - bmp.width * scale) / 2f
            val dy = (height - bmp.height * scale) / 2f
            imageMatrix.postTranslate(dx, dy)
            invalidate()
        }
    }

    fun getCroppedBitmap(): Bitmap? {
        val bmp = bitmap ?: return null
        val frameRect = getFrameRect()

        val result = Bitmap.createBitmap(frameRect.width().toInt(), frameRect.height().toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // Translate the matrix so that the frame's top-left is at (0,0) in the result bitmap
        val matrix = Matrix(imageMatrix)
        matrix.postTranslate(-frameRect.left, -frameRect.top)
        
        canvas.drawBitmap(bmp, matrix, bitmapPaint)
        return result
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        scaleDetector.onTouchEvent(event)
        
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(imageMatrix)
                startPoint.set(event.x, event.y)
                mode = MODE_DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(imageMatrix)
                    midPoint(midPoint, event)
                    mode = MODE_ZOOM
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_DRAG) {
                    val nextMatrix = Matrix(savedMatrix)
                    nextMatrix.postTranslate(event.x - startPoint.x, event.y - startPoint.y)
                    imageMatrix.set(nextMatrix)
                    // Strict clamp during drag to prevent black borders
                    applyConstraints(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = MODE_NONE
            }
        }
        invalidate()
        return true
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        
        // Apply fluid constraints (magnetism and black border prevention)
        if (mode == MODE_NONE) {
            applyConstraints(true)
        }

        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)

        // Draw overlay frame matching the view's aspect ratio (which is full screen)
        val vWidth = width.toFloat()
        val vHeight = height.toFloat()
        val frameRect = getFrameRect()
        
        // Dim outside the frame
        val path = Path().apply {
            addRect(0f, 0f, vWidth, vHeight, Path.Direction.CW)
            addRect(frameRect, Path.Direction.CCW)
        }
        canvas.drawPath(path, maskPaint)
        canvas.drawRect(frameRect, framePaint)
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_ZOOM = 2
    }
}
