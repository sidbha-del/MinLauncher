package app.readfirst.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A thin A-Z strip down the right edge of the Apps list: touch or drag anywhere in its 56dp column
 * and the list jumps to the first app of the nearest letter. It owns the whole gesture (the pager
 * is told not to steal it), ticks once per letter and reports the finger so the caller can show a
 * bubble. Letters are mono 11sp, 32dp in from the edge, evenly spread over the height.
 */
class AlphaStrip(context: Context, private val p: InkPalette) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 11f * d }
    private var letters: List<String> = emptyList()
    private var active = -1

    /** Called with the letter's index when it changes under the finger, and (-1, y) on release. */
    var onSelect: ((index: Int, y: Float) -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null

    fun setLetters(list: List<String>) {
        if (list == letters) return
        letters = list
        active = -1
        invalidate()
    }

    fun letterAt(index: Int): String = letters.getOrElse(index) { "" }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension((56f * d).toInt(), MeasureSpec.getSize(heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Back-swipe exemption only on the middle 200dp, where a drag along the strip happens.
        if (Build.VERSION.SDK_INT >= 29) {
            val h = b - t
            val half = (100f * d).toInt()
            systemGestureExclusionRects = listOf(Rect(0, maxOf(0, h / 2 - half), r - l, minOf(h, h / 2 + half)))
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTouchStart?.invoke()
                select(ev.y, first = true)
            }
            MotionEvent.ACTION_MOVE -> select(ev.y, first = false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                onSelect?.invoke(-1, ev.y)
                invalidate()
            }
        }
        return true
    }

    private fun select(y: Float, first: Boolean) {
        if (letters.isEmpty()) return
        val i = AlphaIndex.indexAt(y, height.toFloat(), letters.size)
        if (i != active || first) {
            if (i != active) {
                performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
                )
            }
            active = i
            invalidate()
        }
        onSelect?.invoke(i, y)
    }

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val pitch = height.toFloat() / letters.size
        // Squeezed (landscape): every other letter is drawn, the touch mapping is unchanged.
        val step = if (pitch < 14f * d) 2 else 1
        val x = width - 32f * d
        for (i in letters.indices) {
            if (step == 2 && i % 2 == 1 && i != active) continue
            val on = i == active
            paint.typeface = if (on) Fonts.monoBold else Fonts.mono
            paint.color = if (on) p.text else p.soft
            val y = pitch * (i + 0.5f) - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(letters[i], x, y, paint)
        }
    }
}
