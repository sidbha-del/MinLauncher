package app.readfirst.reader

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Draws one page of a laid-out chapter and turns taps and swipes into page turns: left third =
 * back, right third = forward, centre = menu. PDF pages share the same [TouchPager].
 */
class PageView(context: Context, private val pager: TouchPager) : View(context) {
    private var chapter: FlowEngine.ChapterLayout? = null
    private var lines: IntRange = IntRange.EMPTY
    var textColor: Int = 0

    fun show(cl: FlowEngine.ChapterLayout, page: Int) {
        chapter = cl
        lines = cl.lineRange(page)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cl = chapter ?: return
        if (lines.isEmpty()) return
        val layout = cl.layout
        layout.paint.color = textColor
        val top = layout.getLineTop(lines.first)
        val bottom = layout.getLineBottom(lines.last)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.clipRect(0, 0, layout.width, bottom - top)
        canvas.translate(0f, -top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = pager.onTouch(this, event)
}

/** Shared tap-zone and swipe logic for text and PDF pages. */
class TouchPager(context: Context, val onNext: () -> Unit, val onPrev: () -> Unit, val onMenu: () -> Unit) {
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipe = 56 * context.resources.displayMetrics.density

    fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                when {
                    abs(dx) > swipe && abs(dx) > abs(dy) -> if (dx < 0) onNext() else onPrev()
                    abs(dx) < slop * 2 && abs(dy) < slop * 2 -> {
                        val third = v.width / 3f
                        when {
                            e.x < third -> onPrev()
                            e.x > 2 * third -> onNext()
                            else -> onMenu()
                        }
                        v.performClick()
                    }
                }
            }
        }
        return true
    }
}
