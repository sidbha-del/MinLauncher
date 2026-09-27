package app.readfirst.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.readfirst.HomeActivity
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Library, Home and Apps side by side — three pages wide, dragged with the finger and settling on
 * release. Home is the centre (index 1) and where the pager opens; Library sits to its left,
 * Apps to its right, matching "swipe left for Apps, swipe right for Library" everywhere else this
 * is described. This is the new base of [HomeActivity.stack].
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

        val pageViews = listOf(library.build(), home.build(), apps.build())
        val t = PagerTrack(host) { p.animate }
        pageViews.forEach { t.addView(it) }
        track = t

        val dockSlot = ui.vertical()
        val dots = dotsRow(ui, p)

        fun showPage(page: Int) {
            currentPage = page
            dockSlot.removeAllViews()
            if (page == 1 || host.prefs.dockEverywhere) dockSlot.addView(Dock.build(host))
            updateDots(dots, p, page.toFloat())
            applyPageTurn(pageViews, page.toFloat(), p.animate)
        }

        t.onProgress = { progress ->
            updateDots(dots, p, progress)
            applyPageTurn(pageViews, progress, p.animate)
        }
        t.onSettled = { page -> showPage(page) }

        val viewport = FrameLayout(host).apply { clipChildren = true }
        viewport.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        t.snapTo(currentPage)
        showPage(currentPage)
        excludeEdgesFromBackGesture(viewport)

        outer.addView(viewport, ui.lp(h = 0, weight = 1f))
        outer.addView(dockSlot)
        outer.addView(dots)
        return outer
    }

    /** Re-centres on Home without animating: called after [HomeActivity.goHome] pops back to the
     *  pager, which is never visible at that moment (something was pushed on top of it), so a jump
     *  cut is both correct and the only option — animating a hidden view would be wasted work. */
    fun centerOnHome() {
        currentPage = 1
        track?.snapTo(1)
    }

    override fun onTimeTick() = home.onTimeTick()
    override fun onHide() {
        apps.onHide()
        home.onHide()
        library.onHide()
    }
    override fun blocksRebuild(): Boolean = track?.inDrag == true

    private fun dotsRow(ui: Ui, p: InkPalette): LinearLayout = ui.horizontal().apply {
        gravity = Gravity.CENTER
        setPadding(0, ui.dp(8), 0, ui.dp(12))
        repeat(3) {
            val dot = View(host).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.soft) } }
            addView(dot, LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
    }

    private fun updateDots(row: LinearLayout, p: InkPalette, progress: Float) {
        val nearest = progress.roundToInt().coerceIn(0, 2)
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? GradientDrawable)?.setColor(if (i == nearest) p.text else p.soft)
        }
    }

    /** A minimal page-turn: the page settling into view eases up from a slight shrink, and the
     *  pages nearest the centre pick up a touch of elevation, using only plain View properties —
     *  no canvas work. Off entirely when [p.animate] is false (Black ink / e-ink). */
    private fun applyPageTurn(pages: List<View>, progress: Float, animate: Boolean) {
        pages.forEachIndexed { i, v ->
            if (!animate) {
                v.scaleX = 1f; v.scaleY = 1f; v.elevation = 0f
                return@forEachIndexed
            }
            val dist = abs(progress - i).coerceIn(0f, 1f)
            val scale = 1f - dist * 0.04f
            v.scaleX = scale
            v.scaleY = scale
            v.elevation = (1f - dist * 2f).coerceAtLeast(0f) * 8f * v.resources.displayMetrics.density
        }
    }

    /** Android 10+'s predictive-back edge gesture would otherwise compete with a page-turn drag
     *  started near the screen edge; there's nothing for Back to do on a launcher anyway. */
    private fun excludeEdgesFromBackGesture(view: View) {
        if (Build.VERSION.SDK_INT < 29) return
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val w = r - l; val h = b - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val edge = (24 * v.resources.displayMetrics.density).roundToInt()
            v.systemGestureExclusionRects = listOf(Rect(0, 0, edge, h), Rect(w - edge, 0, w, h))
        }
    }
}

/**
 * The horizontal drag surface: three same-width pages laid out side by side, translated as one
 * unit. Only claims a touch once it's clearly horizontal (matching [GestureFrame]'s own
 * dominance heuristic), so a page's own vertical scrolling — or Home's down-swipe for the
 * notification shade, handled by its own nested [GestureFrame] — is never stolen.
 */
private class PagerTrack(context: Context, private val canAnimate: () -> Boolean) : ViewGroup(context) {
    var onSettled: ((Int) -> Unit)? = null
    var onProgress: ((Float) -> Unit)? = null
    val inDrag get() = isDragging

    private var currentPage = 1
    private var pageWidth = 0
    private var isDragging = false
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var startTranslation = 0f
    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

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

    /** Instant, no animation — safe to call from a fresh rebuild, never mid-drag. */
    fun snapTo(page: Int) {
        currentPage = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        if (!isDragging && pageWidth > 0) translationX = -currentPage * pageWidth.toFloat()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; tracking = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!tracking && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                    tracking = true
                    beginDrag()
                }
                if (tracking) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) { tracking = true; beginDrag() }
                }
                if (tracking && pageWidth > 0) {
                    val w = pageWidth.toFloat()
                    var t = startTranslation + (ev.x - downX)
                    val min = -(childCount - 1) * w
                    if (t > 0f) t *= 0.3f
                    if (t < min) t = min + (t - min) * 0.3f
                    translationX = t
                    onProgress?.invoke(-t / w)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) endDrag(ev)
                velocityTracker?.recycle()
                velocityTracker = null
                tracking = false
            }
        }
        return true
    }

    private fun beginDrag() {
        isDragging = true
        settleAnimator?.cancel()
        startTranslation = translationX
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
    }

    private fun endDrag(ev: MotionEvent) {
        val w = pageWidth.toFloat().coerceAtLeast(1f)
        velocityTracker?.computeCurrentVelocity(1000)
        val vx = velocityTracker?.xVelocity ?: 0f
        val dx = ev.x - downX
        var target = currentPage
        when {
            vx < -600 || dx < -w * 0.25f -> target += 1
            vx > 600 || dx > w * 0.25f -> target -= 1
        }
        settleTo(target.coerceIn(0, childCount - 1))
    }

    private fun settleTo(page: Int) {
        val w = pageWidth.toFloat()
        val from = translationX
        val to = -page * w
        settleAnimator?.cancel()
        if (!canAnimate() || w <= 0f) {
            translationX = to
            currentPage = page
            isDragging = false
            onProgress?.invoke(page.toFloat())
            onSettled?.invoke(page)
            return
        }
        settleAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                translationX = it.animatedValue as Float
                onProgress?.invoke(-translationX / w)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentPage = page
                    isDragging = false
                    onSettled?.invoke(page)
                }
            })
            start()
        }
    }
}
