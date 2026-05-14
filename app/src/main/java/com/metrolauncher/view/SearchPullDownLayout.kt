package com.metrolauncher.view

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.abs

/**
 * Container that hosts a [SearchBarView] "physically above" the Start page.
 *
 *  Internal layout (vertical):
 *    [child 0]  SearchBarView           (height = barHeight, fixed)
 *    [child 1]  Start screen content    (ScrollView with TileGridLayout)
 *
 *  The bar is initially HIDDEN: it is translated off-screen upwards 
 *  (translationY = -barHeight) and the content below occupies the entire viewport.
 *  When the user overscrolls downwards on the top of the ScrollView, the 
 *  layout consumes the delta by pulling down the content and revealing the bar. 
 *  At the end of the gesture, it snaps to the "open" (1.0) or "closed" (0.0) 
 *  state based on the threshold.
 *
 *  Implementation via NestedScrollingParent3 — the ScrollView inside is 
 *  nested-scroll enabled by default, so its scroll events are offered to us first 
 *  via onNestedPreScroll (to consume when bar is open + finger moves up) 
 *  and later via onNestedScroll (to consume when bar is closed + finger moves down 
 *  + ScrollView is at the top).
 *
 *  The semi-transparent scrim over the content increases with openProgress 
 *  to give depth (design request no. iii).
 */
class SearchPullDownLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle), NestedScrollingParent3 {

    /** Bar height in px, calculated from dp. */
    private var barHeight: Int = 0
    private var statusBarHeight: Int = 0

    /** 0..1 — open state. 0 = closed, 1 = fully visible. */
    private var openProgress: Float = 0f

    private lateinit var searchBar: SearchBarView
    private lateinit var contentView: View

    private val scrim = View(context).apply {
        setBackgroundColor(0xFF000000.toInt())
        alpha = 0f
        // The scrim should never intercept events: let everything pass to the 
        // content below. But when openProgress is 1.0 I want a tap on the 
        // scrim to CLOSE the bar. We handle this via the parent's onTouchEvent.
        isClickable = false
        isFocusable = false
    }

    private val parentHelper = NestedScrollingParentHelper(this)
    private var settleAnimator: ValueAnimator? = null

    private var velocityTracker: VelocityTracker? = null
    private var lastTouchY: Float = 0f
    private var isDragging = false
    private val touchSlop: Int

    init {
        // Bar height: 54dp (era 72dp, ridotto del 25% su richiesta)
        val baseHeight = (54 * resources.displayMetrics.density).toInt()
        barHeight = baseHeight
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarHeight = systemBars.top
            barHeight = baseHeight + statusBarHeight

            // Apply padding to searchBar so it doesn't end up UNDER the status bar text
            if (::searchBar.isInitialized) {
                searchBar.setPadding(0, statusBarHeight, 0, 0)
                // Update bar height in layout params to include the extra space
                val lp = searchBar.layoutParams
                if (lp != null && lp.height != barHeight) {
                    lp.height = barHeight
                    searchBar.layoutParams = lp
                }
                applyProgress()
            }
            insets
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Children declared in XML: index 0 must be the SearchBarView, index 1 the content.
        require(childCount == 2) {
            "SearchPullDownLayout wants exactly 2 children: search bar + content"
        }
        val first = getChildAt(0)
        val second = getChildAt(1)
        require(first is SearchBarView) {
            "First child must be SearchBarView (was ${first.javaClass.simpleName})"
        }
        searchBar = first
        contentView = second

        // Insert the scrim BEFORE the content (bottom in z-order)?
        // No: we want it ABOVE the content but BELOW the bar. So between index 1 and 2.
        // More practical: remove content + scrim, re-add them in the correct order.
        removeView(contentView)
        addView(contentView, 1)
        addView(scrim, 2, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Force the bar layout to barHeight at the top
        val barLp = searchBar.layoutParams ?: LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, barHeight
        )
        barLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        barLp.height = barHeight
        searchBar.layoutParams = barLp

        applyProgress()
    }

    /** Updates translationY/alpha based on [openProgress]. */
    private fun applyProgress() {
        if (!::searchBar.isInitialized) return
        // Bar starts at -barHeight (off-screen top) and goes to 0 (visible)
        searchBar.translationY = -barHeight * (1f - openProgress)
        // Content starts at 0 and moves down by barHeight*progress
        contentView.translationY = barHeight * openProgress
        // Scrim: alpha grows with progress (max 0.3)
        scrim.alpha = 0.3f * openProgress
        scrim.translationY = contentView.translationY
        // When fully open, the bar must be "tappable"; if closed 
        // we don't want it to intercept accidental touches (e.g. status bar pull).
        searchBar.visibility = if (openProgress > 0.001f) VISIBLE else INVISIBLE
        
        // Pass progress to the bar for internal staggered animation
        searchBar.transitionProgress = openProgress
    }

    private fun setProgress(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        if (clamped == openProgress) return
        openProgress = clamped
        applyProgress()
    }

    /** Animates the opening state to 0 or 1 depending on which is closer. */
    fun snapToNearest(velocity: Float = 0f) {
        // Positive velocity = finger moves downwards = wants to open
        val target = when {
            velocity > 800f -> 1f
            velocity < -800f -> 0f
            openProgress >= 0.5f -> 1f
            else -> 0f
        }
        animateTo(target)
    }

    fun close() = animateTo(0f)
    fun open() {
        animateTo(1f)
        // Force notification refresh when opening the bar (menu recall)
        com.metrolauncher.service.NotificationListener.requestRebind()
    }

    private fun animateTo(target: Float) {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(openProgress, target).apply {
            // Duration halved for "snappy" recall — was 300ms.
            duration = (150 * abs(target - openProgress)).toLong().coerceAtLeast(75L)
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { setProgress(it.animatedValue as Float) }
            start()
        }
    }

    val isOpen: Boolean get() = openProgress > 0.5f

    // -------- Scrim tap to close and Swipe UP --------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        
        // If the bar is closed and we're not doing anything special, leave it
        if (!isOpen && openProgress <= 0f) return super.onInterceptTouchEvent(ev)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = ev.y
                isDragging = false
                initVelocityTracker()
                velocityTracker?.addMovement(ev)
                
                // If touch the scrim (below the bar) while open, intercept to close on tap
                if (isOpen && ev.y > barHeight) {
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastTouchY
                // If the bar is open and the user swipes up, intercept the drag
                if (isOpen && dy < -touchSlop) {
                    isDragging = true
                    initVelocityTracker()
                    velocityTracker?.addMovement(ev)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isDragging = false
                recycleVelocityTracker()
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        initVelocityTracker()
        velocityTracker?.addMovement(ev)

        val action = ev.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = ev.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val y = ev.y
                val dy = y - lastTouchY
                if (!isDragging && isOpen && dy < -touchSlop) {
                    isDragging = true
                }
                
                if (isDragging) {
                    // Drag the bar (consumes dy)
                    val deltaProgress = dy / barHeight
                    setProgress(openProgress + deltaProgress)
                }
                lastTouchY = y
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vy = velocityTracker?.yVelocity ?: 0f
                    snapToNearest(vy)
                } else if (isOpen && ev.y > barHeight) {
                    // Simple tap on scrim
                    close()
                }
                isDragging = false
                recycleVelocityTracker()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) snapToNearest()
                isDragging = false
                recycleVelocityTracker()
            }
        }
        return true
    }

    private fun initVelocityTracker() {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // -------- NestedScrollingParent3 --------------------------------------
    // We intercept the scroll of the child ScrollView to use it as a pull-down gesture.
    // Pre-scroll: the user is scrolling, before the ScrollView consumes.
    //   - If the bar is open AND the user moves up (dy>0) → we consume to close first.
    // Post-scroll: the ScrollView could not consume (it is at the top and user moves down).
    //   - In that case (dyUnconsumed<0) we consume to open the bar.

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        return axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parentHelper.onNestedScrollAccepted(child, target, axes, type)
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        parentHelper.onStopNestedScroll(target, type)
        if (type == ViewCompat.TYPE_TOUCH) snapToNearest()
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // dy > 0  → user scrolls content upwards (finger moves up)
        // dy < 0  → user scrolls content downwards (finger moves down)
        if (dy > 0 && openProgress > 0f) {
            // Bar is open or opening: close first, then let scroll.
            val maxConsume = (openProgress * barHeight).toInt()
            val take = dy.coerceAtMost(maxConsume)
            setProgress(openProgress - take.toFloat() / barHeight)
            consumed[1] = take
        }
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int,
                                 dxUnconsumed: Int, dyUnconsumed: Int, type: Int) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, intArrayOf(0, 0))
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int,
                                 dxUnconsumed: Int, dyUnconsumed: Int, type: Int,
                                 consumed: IntArray) {
        // dyUnconsumed < 0 = ScrollView could not consume downward scroll (at top).
        // We take it to open the bar.
        if (dyUnconsumed < 0 && type == ViewCompat.TYPE_TOUCH) {
            val available = -dyUnconsumed
            val maxConsume = ((1f - openProgress) * barHeight).toInt()
            val take = available.coerceAtMost(maxConsume)
            setProgress(openProgress + take.toFloat() / barHeight)
            consumed[1] = -take
        }
    }

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean {
        // If the bar is open AND the user flings upwards, close.
        if (isOpen && velocityY > 800f) {
            close()
            return true
        }
        return false
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean = false

    override fun getNestedScrollAxes(): Int = parentHelper.nestedScrollAxes
}
