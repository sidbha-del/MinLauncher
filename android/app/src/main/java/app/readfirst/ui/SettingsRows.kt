package app.readfirst.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The calmer Settings look: 56dp rows with one line of title and at most one short line under
 * it, flat switches, and hairlines only between sections. Anything that needs a sentence of
 * explanation opens a small sheet ([infoSheet]) instead of sitting under the row.
 */

/** A flat 40x22dp switch: hollow with a square knob left when off, ink-filled with a page-coloured knob right when on. */
class FlatSwitch(context: Context, private val ui: Ui, val on: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ui.dp(40), ui.dp(22))
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = ui.dp(1.5f).toFloat()
        val half = stroke / 2
        if (on) {
            paint.style = Paint.Style.FILL
            paint.color = ui.p.text
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = ui.p.text
            canvas.drawRect(half, half, width - half, height - half, paint)
        }
        val knob = ui.dp(12).toFloat()
        val inset = (height - knob) / 2
        val left = if (on) width - inset - knob else inset
        paint.style = Paint.Style.FILL
        paint.color = if (on) ui.p.bg else ui.p.text
        canvas.drawRect(left, inset, left + knob, inset + knob, paint)
    }
}

/** A section: generous space above, a small mono header, one hairline below. */
fun Ui.section(label: String, first: Boolean = false): View = vertical().apply {
    addView(mono(label, 10f).apply { setPadding(dp(18), dp(if (first) 18 else 30), dp(18), dp(8)) })
    addView(rule())
}

/**
 * A one-line setting: [title], an optional one-line [subtitle], and an optional [value] on the
 * right. Tapping runs [onClick]; without one the row is plain information.
 */
fun Ui.setting(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    trailing: View? = null,
    trailingWidth: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    onClick: (() -> Unit)? = null,
): View {
    val row = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }
    val names = vertical()
    names.addView(text(title, 16f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    if (subtitle != null) {
        names.addView(text(subtitle, 13f, p.soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.also { margins(it, t = 2) })
    }
    row.addView(names, lp(0, weight = 1f))
    if (value != null) {
        row.addView(
            text(value, 14f, p.soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) },
        )
    }
    if (trailing != null) {
        row.addView(trailing, LinearLayout.LayoutParams(if (trailingWidth > 0) dp(trailingWidth) else trailingWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
    }
    if (onClick != null) tappable(row, onClick)
    return row
}

/** A [setting] whose right side is a flat switch; tapping anywhere on the row flips it through [onToggle]. */
fun Ui.switchSetting(title: String, subtitle: String? = null, on: Boolean, onToggle: (Boolean) -> Unit): View {
    val row = setting(title, subtitle, trailing = FlatSwitch(context, this, on)) { onToggle(!on) }
    row.contentDescription = "$title, ${if (on) "on" else "off"}" + (subtitle?.let { ", $it" } ?: "")
    return row
}

/**
 * A small sheet: a mono [title], a [body] of a line or two, and an optional bordered [actionLabel]
 * button. [host] is only used to show and dismiss the sheet.
 */
fun Ui.infoSheet(host: app.readfirst.HomeActivity, title: String, body: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
    val col = vertical().apply { setPadding(0, 0, 0, dp(18)) }
    col.addView(mono(title, 11f).also { margins(it, 18, 16, 18, 10) })
    col.addView(text(body, 14f, p.soft).apply { setLineSpacing(0f, 1.3f) }.also { margins(it, 18, 0, 18, 16) })
    if (actionLabel != null && action != null) {
        col.addView(button(actionLabel) {
            host.dismissSheet()
            action()
        }.also { margins(it, 18, 0, 18, 0) })
    }
    host.showSheet(col)
}
