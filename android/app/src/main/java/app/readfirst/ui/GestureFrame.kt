package app.readfirst.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * A FrameLayout that turns a deliberate swipe down into navigation while leaving taps (and
 * scrolling) to its children. It only intercepts once a drag is clearly vertical, so a scrolling
 * list inside still scrolls. Horizontal paging between Home, Library and Apps is owned by
 * [Pager], not this class — the two never compete for the same gesture.
 */
class GestureFrame(context: Context) : FrameLayout(context) {
    var onSwipeDown: (() -> Unit)? = null
    /** Long-press on empty space (children that handle touches keep their own long-press). */
    var onLongPress: (() -> Unit)? = null

    /**
     * Asked before a downward swipe is claimed. Answer true while the content can still scroll
     * that way and the drag is left to it, so a screen taller than the window stays reachable
     * instead of every drag becoming navigation.
     */
    var contentScrolls: ((Int) -> Boolean)? = null

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longFired = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val distance = 64 * resources.displayMetrics.density
    private val longPress = Runnable {
        longFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val vertical = abs(dy) > slop && abs(dy) > abs(dx) * 1.5f && dy > 0 && onSwipeDown != null &&
                    contentScrolls?.invoke(-1) != true
                if (!dragging && vertical) dragging = true
                if (dragging) {
                    removeCallbacks(longPress)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longFired = false
                if (onLongPress != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val dy = ev.y - downY
                if (!longFired && dy > distance) onSwipeDown?.invoke()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                dragging = false
            }
        }
        return true
    }
}
