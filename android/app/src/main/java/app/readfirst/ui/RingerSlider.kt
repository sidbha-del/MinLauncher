package app.readfirst.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.accessibility.AccessibilityNodeInfo
import app.readfirst.HomeActivity
import app.readfirst.data.Ringer

/**
 * A slim vertical control on Home's right edge for the phone's ringer: ring at the top, vibrate in
 * the middle, silent at the bottom. Tap a notch to go straight to it, or drag the knob; it snaps
 * to the nearest notch with a light tick, and the change is made on release.
 *
 * Android ties Silent to Do Not Disturb access ([Ringer.needsAccess]). Without it, Silent asks
 * once, through [Ringer.askAccess], and is refused after that; leaving Silent without access
 * always asks, since there is no other way out. It follows changes made elsewhere (volume keys,
 * the tile, the notification) through [sync], which Home calls from the same watch as before.
 */
class RingerSlider(private val host: HomeActivity) : View(host) {
    private val p = host.ui.p
    private val d = resources.displayMetrics.density
    /** On the left edge instead of the right: everything is drawn and hit-tested mirrored. */
    private val left = host.prefs.ringerSliderLeft
    private val hasVibrator = Ringer.hasVibrator(host)
    private val count = RingerNotches.count(hasVibrator)
    private val notchPx = 56f * d
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The notch the phone is on. */
    private var notch = RingerNotches.notchFor(Ringer.mode(host).ordinal, hasVibrator)
    private var access = Ringer.hasAccess(host)
    private var dragging = false
    private var hover = notch
    private var knobY = RingerNotches.centerY(notch, notchPx)
    private var mover: ValueAnimator? = null

    init {
        contentDescription = "Ringer: ${Ringer.mode(host).label}"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Follows the phone's ringer. Ignored while the finger is on the knob. */
    fun sync(mode: Ringer.Mode, hasAccess: Boolean) {
        val n = RingerNotches.notchFor(mode.ordinal, hasVibrator)
        val label = "Ringer: ${mode.label}"
        if (contentDescription != label) contentDescription = label
        if (dragging) return
        if (n == notch && hasAccess == access) return
        notch = n
        access = hasAccess
        hover = n
        moveKnobTo(n, animate = false)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension((44f * d).toInt(), (count * notchPx).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Only this control, 168dp or less tall, is exempt from the system's back swipe; a
        // full-height strip would exceed the 200dp the system honours per edge.
        if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, r - l, b - t))
    }

    // ---- touch --------------------------------------------------------------------------------

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The sliver on the screen-edge side is not ours: it belongs to the page beneath.
                if (if (left) ev.x < width - 34f * d else ev.x > 34f * d) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                mover?.cancel()
                dragging = true
                hover = RingerNotches.nearest(ev.y, notchPx, count)
                knobY = RingerNotches.clampKnob(ev.y, notchPx, count)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                knobY = RingerNotches.clampKnob(ev.y, notchPx, count)
                val near = RingerNotches.nearest(knobY, notchPx, count)
                if (near != hover) {
                    hover = near
                    if (refused(near)) reject() else tick()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                commit(RingerNotches.nearest(knobY, notchPx, count))
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                hover = notch
                moveKnobTo(notch)
            }
        }
        return true
    }

    private fun modeOf(n: Int): Ringer.Mode = Ringer.Mode.entries[RingerNotches.modeIndex(n, hasVibrator)]

    /** Silent is out of reach: access was asked for once already and not given. */
    private fun refused(n: Int): Boolean {
        val target = modeOf(n)
        return target == Ringer.Mode.SILENT && target != Ringer.mode(host) && !Ringer.hasAccess(host) && host.prefs.ringerAccessAsked
    }

    private fun commit(target: Int) {
        val current = Ringer.mode(host)
        val mode = modeOf(target)
        hover = notch
        if (mode == current) {
            moveKnobTo(notch)
            return
        }
        if (Ringer.needsAccess(host, mode)) {
            moveKnobTo(notch)
            when {
                current == Ringer.Mode.SILENT -> Ringer.askAccess(host)
                !host.prefs.ringerAccessAsked -> {
                    host.prefs.ringerAccessAsked = true
                    Ringer.askAccess(host)
                }
                else -> reject()
            }
            return
        }
        if (Ringer.set(host, mode)) {
            notch = target
            hover = target
            moveKnobTo(target)
            contentDescription = "Ringer: ${mode.label}"
        } else {
            moveKnobTo(notch)
            reject()
        }
    }

    private fun moveKnobTo(n: Int, animate: Boolean = true) {
        mover?.cancel()
        val to = RingerNotches.centerY(n, notchPx)
        if (!animate || !p.animate || !isAttachedToWindow || abs(knobY - to) < 1f) {
            knobY = to
            invalidate()
            return
        }
        mover = ValueAnimator.ofFloat(knobY, to).apply {
            duration = 80
            interpolator = LinearInterpolator()
            addUpdateListener { knobY = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun abs(v: Float) = if (v < 0f) -v else v

    private fun tick() {
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    private fun reject() {
        if (Build.VERSION.SDK_INT >= 30) performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    // ---- accessibility ------------------------------------------------------------------------

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        commit((notch + step).coerceIn(0, count - 1))
        return true
    }

    // ---- drawing ------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val trackX = if (left) 14f * d else width - 14f * d
        val glyphDx = if (left) 20f * d else -20f * d
        val shown = if (dragging) RingerNotches.nearest(knobY, notchPx, count) else notch
        val top = RingerNotches.centerY(0, notchPx)
        val bottom = RingerNotches.centerY(count - 1, notchPx)

        stroke.color = if (p.isBlack) p.rule else p.ruleSoft
        stroke.strokeWidth = 2f * d
        canvas.drawLine(trackX, top, trackX, bottom, stroke)

        stroke.color = p.soft
        stroke.strokeWidth = 1.5f * d
        for (i in 0 until count) {
            val y = RingerNotches.centerY(i, notchPx)
            canvas.drawLine(trackX - 4f * d, y, trackX + 4f * d, y, stroke)
        }

        for (i in 0 until count) {
            val kind = RingerNotches.modeIndex(i, hasVibrator)
            val quiet = kind == RingerNotches.SILENT && !access
            stroke.color = if (i == shown && !quiet) p.text else p.soft
            stroke.strokeWidth = 1.5f * d
            glyph(canvas, kind, trackX + glyphDx, RingerNotches.centerY(i, notchPx))
        }

        val r = 11f * d
        val silent = RingerNotches.modeIndex(shown, hasVibrator) == RingerNotches.SILENT
        if (silent && p.isBlack) {
            fill.color = p.bg; fill.style = Paint.Style.FILL
            canvas.drawCircle(trackX, knobY, r, fill)
            stroke.color = p.text; stroke.strokeWidth = 1.5f * d
            canvas.drawCircle(trackX, knobY, r - 0.75f * d, stroke)
            canvas.drawLine(trackX - 5f * d, knobY + 5f * d, trackX + 5f * d, knobY - 5f * d, stroke)
        } else {
            fill.style = Paint.Style.FILL
            fill.color = if (silent) p.warn else p.text
            canvas.drawCircle(trackX, knobY, r, fill)
        }
    }

    /** Ten-dp strokes: a bell, three short bars, a circle with a slash. */
    private fun glyph(canvas: Canvas, kind: Int, cx: Float, cy: Float) {
        when (kind) {
            RingerNotches.RING -> {
                canvas.drawArc(RectF(cx - 4f * d, cy - 5f * d, cx + 4f * d, cy + 3f * d), 180f, 180f, false, stroke)
                canvas.drawLine(cx - 4f * d, cy - 1f * d, cx - 5f * d, cy + 3f * d, stroke)
                canvas.drawLine(cx + 4f * d, cy - 1f * d, cx + 5f * d, cy + 3f * d, stroke)
                canvas.drawLine(cx - 5f * d, cy + 3f * d, cx + 5f * d, cy + 3f * d, stroke)
                canvas.drawPoint(cx, cy + 5f * d, stroke)
            }
            RingerNotches.VIBRATE -> {
                canvas.drawLine(cx - 4f * d, cy - 3f * d, cx - 4f * d, cy + 3f * d, stroke)
                canvas.drawLine(cx, cy - 5f * d, cx, cy + 5f * d, stroke)
                canvas.drawLine(cx + 4f * d, cy - 3f * d, cx + 4f * d, cy + 3f * d, stroke)
            }
            else -> {
                canvas.drawCircle(cx, cy, 4.5f * d, stroke)
                canvas.drawLine(cx - 4f * d, cy + 4f * d, cx + 4f * d, cy - 4f * d, stroke)
            }
        }
    }

    override fun onDetachedFromWindow() {
        mover?.cancel()
        super.onDetachedFromWindow()
    }
}
