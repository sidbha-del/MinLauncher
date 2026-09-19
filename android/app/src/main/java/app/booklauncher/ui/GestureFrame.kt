package app.booklauncher.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * A FrameLayout that turns deliberate swipes into navigation while leaving taps (and scrolling in
 * directions it doesn't handle) to its children. It only intercepts once a drag is clearly along
 * an axis it has a callback for, so a scrolling list inside still scrolls.
 */
class GestureFrame(context: Context) : FrameLayout(context) {
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    /** Long-press on empty space (children that handle touches keep their own long-press). */
    var onLongPress: (() -> Unit)? = null

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
                val horizontal = abs(dx) > slop && abs(dx) > abs(dy) * 1.5f && (onSwipeLeft != null || onSwipeRight != null)
                val vertical = abs(dy) > slop && abs(dy) > abs(dx) * 1.5f &&
                    ((dy < 0 && onSwipeUp != null) || (dy > 0 && onSwipeDown != null))
                if (!dragging && (horizontal || vertical)) dragging = true
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
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (longFired) {
                    // The long-press already acted; a finger lifted after it isn't a swipe.
                } else if (abs(dx) > abs(dy)) {
                    if (dx < -distance) onSwipeLeft?.invoke() else if (dx > distance) onSwipeRight?.invoke()
                } else {
                    if (dy < -distance) onSwipeUp?.invoke() else if (dy > distance) onSwipeDown?.invoke()
                }
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
