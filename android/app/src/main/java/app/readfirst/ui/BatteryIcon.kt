package app.readfirst.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * A small battery, drawn: a 20x10dp outline filled to the charge level. The percentage is written
 * only at 20% or less, in the warning colour, which is when it is worth a reader's attention.
 * 40dp tall so it is an easy tap target (it opens the phone's battery settings).
 */
class BatteryIcon(context: Context, private val p: InkPalette) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = -1

    init {
        isClickable = true
    }

    fun setLevel(percent: Int?) {
        val v = percent ?: -1
        if (v == level) return
        val wasLow = isLow(level)
        level = v
        contentDescription = if (v >= 0) "Battery $v percent" else "Battery"
        if (wasLow || isLow(v)) requestLayout() // the percentage's width changes with the digits
        invalidate()
    }

    private fun isLow(v: Int) = v in 0..LOW

    private fun textWidth(): Float {
        if (!isLow(level)) return 0f
        paint.typeface = Fonts.sans
        paint.textSize = 13f * d
        return paint.measureText("$level%") + 6f * d
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension((22f * d + textWidth()).toInt(), (40f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (level < 0) return
        val line = 1.2f * d
        val w = 20f * d
        val h = 10f * d
        val top = (height - h) / 2f
        val low = isLow(level)
        val color = if (low) p.warn else p.text
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = line
        paint.color = color
        canvas.drawRect(RectF(line / 2f, top + line / 2f, w - line / 2f, top + h - line / 2f), paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(w, top + 3f * d, w + 2f * d, top + h - 3f * d, paint)
        val inner = w - 2f * line - 2f * d
        val filled = inner * level.coerceIn(0, 100) / 100f
        if (filled > 0f) canvas.drawRect(line + d, top + line + d, line + d + filled, top + h - line - d, paint)
        if (low) {
            paint.typeface = Fonts.sans
            paint.textSize = 13f * d
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("$level%", w + 8f * d, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    private companion object {
        const val LOW = 20
    }
}
