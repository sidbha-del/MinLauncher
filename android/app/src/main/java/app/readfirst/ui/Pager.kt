package app.readfirst.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import app.readfirst.HomeActivity
import kotlin.math.abs

/**
 * Library, Home and Apps side by side: three pages wide, dragged with the finger and settling on
 * release. Home is the centre (index 1) and where the pager opens; Library sits to its left,
 * Apps to its right. This is the new base of [HomeActivity.stack].
 *
 * Motion is a plain horizontal slide: nothing but the track's translation changes per frame, and
 * nothing is added to or removed from the layout while it moves or when it settles. The dock lives
 * inside the Home page (or, when it shows on every page, once below the track), never swapped in
 * and out at settle, so the track is never re-measured under the finger.
 */
class Pager(host: HomeActivity) : Screen(host) {
    private val library = LibraryScreen(host)
    private val home = HomeScreen(host)
    private val apps = AppsScreen(host)

    private var currentPage = 1
    private var track: PagerTrack? = null

    override fun build(): View {
        val ui = host.ui
        val p = ui.p
        val outer = ui.vertical()
        val everywhere = host.prefs.dockEverywhere

        val homeView = home.build()
        // Home keeps its own dock inside its page; with the dock on every page it is shared below.
        val homePage: View = if (everywhere) homeView else ui.vertical().apply {
            addView(homeView, ui.lp(h = 0, weight = 1f))
            addView(Dock.build(host))
        }
        val pageViews = listOf(library.build(), homePage, apps.build())
        val dots = PageDots(host, p)
        val t = PagerTrack(host, { p.animate }, { p.animate && !host.catalog.isLowRam })
        pageViews.forEach { t.addView(it) }
        track = t

        t.onProgress = { progress -> dots.setActive(PagerMath.nearestPage(progress, pageViews.size)) }
        t.onSettled = { page -> currentPage = page }

        val viewport = FrameLayout(host).apply { clipChildren = true }
        viewport.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        t.snapTo(currentPage)
        dots.setActive(currentPage)

        outer.addView(viewport, ui.lp(h = 0, weight = 1f))
        if (everywhere) outer.addView(Dock.build(host))
        outer.addView(dots, ui.lp(h = ui.dp(24)))
        return outer
    }

    /** Re-centres on Home without animating: called after [HomeActivity.goHome] pops back to the
     *  pager, which is never visible at that moment (something was pushed on top of it), so a jump
     *  cut is both correct and the only option. */
    fun centerOnHome() {
        currentPage = 1
        track?.snapTo(1)
    }

    override fun onTimeTick() = home.onTimeTick()
    override fun onShown() = home.onShown()
    override fun onHide() {
        apps.onHide()
        home.onHide()
        library.onHide()
    }
    override fun blocksRebuild(): Boolean = track?.inDrag == true
}

/** Three small page dots, drawn in one view: the current one filled, the others a quiet fill in
 *  Colour ink and an outline in Black ink (where the two inks would otherwise match). */
private class PageDots(context: Context, private val p: InkPalette) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = -1

    fun setActive(i: Int) {
        if (i == active) return
        active = i
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = 2.5f * d
        val gap = 8f * d
        val total = 3 * 2 * r + 2 * gap
        var x = (width - total) / 2f + r
        val y = height / 2f
        repeat(3) { i ->
            if (i == active) {
                paint.style = Paint.Style.FILL; paint.color = p.text
                canvas.drawCircle(x, y, r, paint)
            } else if (p.isBlack) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, d); paint.color = p.text
                canvas.drawCircle(x, y, r - paint.strokeWidth / 2f, paint)
            } else {
                paint.style = Paint.Style.FILL; paint.color = p.ruleSoft
                canvas.drawCircle(x, y, r, paint)
            }
            x += 2 * r + gap
        }
    }
}

/**
 * The horizontal drag surface: three same-width pages laid out side by side, translated as one
 * unit. Only claims a touch once it's clearly horizontal, so a page's own vertical scrolling, or
 * Home's down-swipe for the notification shade, is never stolen; a child that wants the whole
 * gesture (the ringer slider, the A-Z strip) says so with requestDisallowInterceptTouchEvent.
 *
 * Drag maths uses raw screen coordinates: this view moves under the finger, so its own local
 * coordinates would shift with every frame of the drag.
 */
private class PagerTrack(
    context: Context,
    private val canAnimate: () -> Boolean,
    private val useLayer: () -> Boolean,
) : ViewGroup(context) {
    var onSettled: ((Int) -> Unit)? = null
    var onProgress: ((Float) -> Unit)? = null
    val inDrag get() = isDragging

    private var currentPage = 1
    private var pageWidth = 0
    private var isDragging = false
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var dragOriginX = 0f
    private var startPage = 1
    private var startTranslation = 0f
    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        pageWidth = w
        val exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(exactW, exactH)
        setMeasuredDimension(w * childCount.coerceAtLeast(1), h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var left = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(left, 0, left + pageWidth, child.measuredHeight)
            left += pageWidth
        }
        if (!isDragging) translationX = -currentPage * pageWidth.toFloat()
    }

    /** Instant, no animation: safe to call from a fresh rebuild, never mid-drag. */
    fun snapTo(page: Int) {
        currentPage = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        if (!isDragging && pageWidth > 0) translationX = -currentPage * pageWidth.toFloat()
    }

    private fun track(ev: MotionEvent) {
        // Velocity in screen coordinates, for the same reason as the drag itself.
        val vt = velocityTracker ?: return
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        vt.addMovement(copy)
        copy.recycle()
    }

    private fun newTracker(ev: MotionEvent) {
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain()
        track(ev)
    }

    private fun crossedSlop(ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        return abs(dx) > slop && abs(dx) > abs(dy) * 1.5f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; tracking = false
                newTracker(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!tracking && crossedSlop(ev)) {
                    tracking = true
                    beginDrag(ev)
                }
                if (tracking) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                if (velocityTracker == null) newTracker(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (!tracking && crossedSlop(ev)) { tracking = true; beginDrag(ev) }
                if (tracking && pageWidth > 0) {
                    val w = pageWidth.toFloat()
                    var t = startTranslation + (ev.rawX - dragOriginX)
                    val min = -(childCount - 1) * w
                    if (t > 0f) t *= 0.3f
                    if (t < min) t = min + (t - min) * 0.3f
                    translationX = t
                    onProgress?.invoke(-t / w)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                track(ev)
                if (tracking) endDrag(ev, cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL)
                velocityTracker?.recycle()
                velocityTracker = null
                tracking = false
            }
        }
        return true
    }

    private fun stopSettle() {
        settleAnimator?.let {
            // Without these, cancel() would still run the end listener: onSettled, a page change and
            // isDragging flipped back, all in the middle of a new drag.
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        settleAnimator = null
    }

    private fun beginDrag(ev: MotionEvent) {
        stopSettle()
        isDragging = true
        // Re-grabbing a page still settling: the page it is nearest is the one the drag starts from.
        val w = pageWidth.toFloat().coerceAtLeast(1f)
        currentPage = PagerMath.nearestPage(-translationX / w, childCount)
        startPage = currentPage
        startTranslation = translationX
        // The drag starts where the finger crossed the slop, so the page does not jump under it.
        dragOriginX = ev.rawX
        if (useLayer()) setLayerType(LAYER_TYPE_HARDWARE, null)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun endDrag(ev: MotionEvent, cancelled: Boolean) {
        val w = pageWidth.toFloat().coerceAtLeast(1f)
        velocityTracker?.computeCurrentVelocity(1000)
        val vx = velocityTracker?.xVelocity ?: 0f
        val dx = ev.rawX - downX
        val target = if (cancelled) startPage
        else PagerMath.targetPage(startPage, dx, vx, w, density, childCount)
        settleTo(target, if (cancelled) 0f else vx)
    }

    private fun finishSettle(page: Int) {
        currentPage = page
        isDragging = false
        if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
        onProgress?.invoke(page.toFloat())
        onSettled?.invoke(page)
    }

    private fun settleTo(page: Int, vx: Float) {
        val w = pageWidth.toFloat()
        val from = translationX
        val to = -page * w
        stopSettle()
        if (!canAnimate() || w <= 0f) {
            translationX = to
            finishSettle(page)
            return
        }
        settleAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = PagerMath.settleDurationMs(to - from, w, vx).toLong()
            interpolator = ease
            addUpdateListener {
                translationX = it.animatedValue as Float
                onProgress?.invoke(-translationX / w)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    settleAnimator = null
                    finishSettle(page)
                }
            })
            start()
        }
    }
}
