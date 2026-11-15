package app.readfirst.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.readfirst.data.FlipTimer

/**
 * The round timer button beside Home's clock: 48dp, drawn entirely here (no icon font), and a
 * fixed size so that what it says changing never moves the clock.
 *
 * Idle it is an outlined circle with an hourglass; running it is a filled circle with the time
 * left (`mm:ss`, `h:mm`, or `18m` in Black ink); armed, waiting for the phone to be put face-down,
 * it is hollow with a dot at twelve o'clock. Tapping it opens the timer sheet, set up by the caller.
 */
class TimerButton(context: Context, private val p: InkPalette) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Path()
    private var state: FlipTimer.State = FlipTimer.IDLE
    private var digits = ""

    init {
        isClickable = true
        contentDescription = "Timer"
    }

    /** Shows [s]; redraws only, never re-measures. */
    fun update(s: FlipTimer.State) {
        val text = if (s.phase == FlipTimer.Phase.RUNNING || s.phase == FlipTimer.Phase.RINGING)
            TimerDigits.format(s.remainingMs(), p.isBlack) else ""
        val label = when (s.phase) {
            FlipTimer.Phase.IDLE -> "Timer"
            FlipTimer.Phase.ARMED -> "Timer waiting for the phone to be put face-down"
            FlipTimer.Phase.RUNNING -> {
                val mins = ((s.remainingMs() + 59_999) / 60_000).toInt()
                "Timer $mins ${if (mins == 1) "minute" else "minutes"} left"
            }
            FlipTimer.Phase.RINGING -> "Timer finished"
        }
        if (label != contentDescription) contentDescription = label
        if (s == state && text == digits) return
        state = s
        digits = text
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val size = (48f * d).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 22f * d
        val line = 1.5f * d
        when (state.phase) {
            FlipTimer.Phase.RUNNING, FlipTimer.Phase.RINGING -> {
                paint.style = Paint.Style.FILL
                paint.color = p.text
                canvas.drawCircle(cx, cy, r, paint)
                paint.color = p.bg
                paint.typeface = Fonts.mono
                paint.textSize = 11f * d
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(digits, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
            }
            else -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = line
                paint.color = p.text
                canvas.drawCircle(cx, cy, r - line / 2f, paint)
                if (state.phase == FlipTimer.Phase.ARMED) {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy - r + 7f * d, 1.5f * d, paint)
                }
                hourglass(canvas, cx, cy)
            }
        }
    }

    /** Two triangles meeting at a waist, with a cap at each end: 14dp wide, 20dp tall. */
    private fun hourglass(canvas: Canvas, cx: Float, cy: Float) {
        val hw = 7f * d
        val hh = 10f * d
        glass.reset()
        glass.moveTo(cx - hw, cy - hh)
        glass.lineTo(cx + hw, cy - hh)
        glass.lineTo(cx, cy)
        glass.lineTo(cx + hw, cy + hh)
        glass.lineTo(cx - hw, cy + hh)
        glass.lineTo(cx, cy)
        glass.close()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * d
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = p.text
        canvas.drawPath(glass, paint)
    }
}
