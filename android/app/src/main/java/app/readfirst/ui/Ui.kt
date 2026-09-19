package app.readfirst.ui

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.readfirst.data.Ink
import app.readfirst.data.Page
import app.readfirst.data.PageStyle
import kotlin.math.roundToInt

/**
 * One ink on one page: every colour a screen uses. Screens read colours from here, never
 * literals, so ink, page colour and dark pages apply everywhere at once.
 */
class InkPalette(
    val ink: Ink,
    val style: PageStyle,
    val bg: Int,
    val text: Int,
    val soft: Int,
    val rule: Int,
    val ruleSoft: Int,
    val fill: Int,
    val warn: Int,
    val pressed: Int,
) {
    val isBlack get() = ink == Ink.BLACK
    /** A dark page (Night): light ink, light status-bar icons, inverted mono icons. */
    val dark get() = style.page.dark
    /** Black ink is e-ink-safe: no animation anywhere. */
    val animate get() = ink == Ink.COLOR

    /** Applied to covers and app icons: muted colour on paper, or grayscale for black ink. */
    val imageFilter: ColorMatrixColorFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            if (ink == Ink.BLACK) {
                setSaturation(0f)
                val c = 1.2f
                val t = (1 - c) * 128
                postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
            } else {
                setSaturation(0.8f)
            }
        },
    )

    companion object {
        fun of(ink: Ink, style: PageStyle): InkPalette {
            val page = style.page
            val bg = when (page) {
                Page.DEFAULT -> if (ink == Ink.BLACK) Page.WHITE.color else Page.PAPER.color
                else -> page.color
            }
            return when {
                ink == Ink.COLOR && !page.dark -> InkPalette(
                    ink, style, bg, text = 0xFF29251F.toInt(), soft = 0xFF7B7267.toInt(),
                    rule = 0xFF29251F.toInt(), ruleSoft = blend(0xFF29251F.toInt(), bg, 0.13f), fill = 0xFF2B4C7E.toInt(),
                    warn = 0xFF9B2D20.toInt(), pressed = 0x1A29251F,
                )
                ink == Ink.COLOR -> InkPalette(
                    ink, style, bg, text = 0xFFE6DFD2.toInt(), soft = 0xFFA39A8C.toInt(),
                    rule = 0xFFE6DFD2.toInt(), ruleSoft = blend(0xFFE6DFD2.toInt(), bg, 0.16f), fill = 0xFF8FB0E6.toInt(),
                    warn = 0xFFE08A74.toInt(), pressed = 0x26E6DFD2,
                )
                !page.dark -> InkPalette(
                    ink, style, bg, text = 0xFF000000.toInt(), soft = 0xFF3A3A3A.toInt(),
                    rule = 0xFF000000.toInt(), ruleSoft = 0xFF000000.toInt(), fill = 0xFF000000.toInt(),
                    warn = 0xFF000000.toInt(), pressed = 0x26000000,
                )
                else -> InkPalette(
                    ink, style, bg, text = 0xFFFFFFFF.toInt(), soft = 0xFFCFCFCF.toInt(),
                    rule = 0xFFFFFFFF.toInt(), ruleSoft = 0xFFFFFFFF.toInt(), fill = 0xFFFFFFFF.toInt(),
                    warn = 0xFFFFFFFF.toInt(), pressed = 0x33FFFFFF,
                )
            }
        }

        /** [a] mixed into [b] at [amount] (0 = b, 1 = a). */
        fun blend(a: Int, b: Int, amount: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * amount + ((b shr shift) and 0xFF) * (1 - amount)).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

object Fonts {
    val serif: Typeface = Typeface.SERIF
    val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val serifItalic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    val mono: Typeface = Typeface.MONOSPACE
    val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val sansBold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
}

/** View factories for the ink design language: rules, mono labels, boxed buttons, rows. */
class Ui(val context: Context, val p: InkPalette) {
    val density = context.resources.displayMetrics.density

    fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

    fun text(value: CharSequence, sizeSp: Float, color: Int = p.text, face: Typeface = Fonts.sans): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    /** Small uppercase monospace label: bars, section headers, data. */
    fun mono(value: String, sizeSp: Float = 11f, color: Int = p.soft): TextView =
        text(value.uppercase(), sizeSp, color, Fonts.mono).apply { letterSpacing = 0.06f }

    fun serif(value: CharSequence, sizeSp: Float, bold: Boolean = false, italic: Boolean = false, color: Int = p.text): TextView =
        text(value, sizeSp, color, if (bold) Fonts.serifBold else if (italic) Fonts.serifItalic else Fonts.serif).apply {
            setLineSpacing(0f, 1.25f)
        }

    fun rule(soft: Boolean = false, thicknessDp: Float = 1f): View = View(context).apply {
        setBackgroundColor(if (soft) p.ruleSoft else p.rule)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(thicknessDp)))
    }

    fun vertical(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    /**
     * Sets margins in dp. A view without layout params gets full-width ones, which is right in a
     * vertical column but squeezes siblings in a horizontal row: there, pass explicit params.
     */
    fun margins(view: View, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
        (view.layoutParams as? ViewGroup.MarginLayoutParams ?: lp()).let {
            it.setMargins(dp(l), dp(t), dp(r), dp(b))
            view.layoutParams = it
        }
        return view
    }

    /** Pressed feedback without ripple animation (instant on e-ink). */
    fun tapBackground(base: Int? = null): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(p.pressed))
        addState(intArrayOf(), if (base != null) ColorDrawable(base) else ColorDrawable(0))
    }

    fun tappable(view: View, onClick: (() -> Unit)?, onLong: (() -> Unit)? = null): View {
        if (onClick != null) view.setOnClickListener { onClick() }
        if (onLong != null) view.setOnLongClickListener { onLong(); true }
        if (onClick != null || onLong != null) {
            if (view.background == null) view.background = tapBackground()
            view.isClickable = true
        }
        return view
    }

    /** The mono bar at the top of every screen: three slots, a rule below. */
    fun topBar(
        left: String,
        center: String = "",
        right: String = "",
        onRight: (() -> Unit)? = null,
        onLong: (() -> Unit)? = null,
        rightIcon: Int? = null,
        rightIconLabel: String = "",
    ): LinearLayout {
        val bar = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(if (rightIcon != null) 2 else 12), dp(if (rightIcon != null) 6 else 18), dp(if (rightIcon != null) 0 else 10))
        }
        val hasCenter = center.isNotEmpty()
        val hasRight = right.isNotEmpty() || rightIcon != null
        bar.addView(mono(left, 12f, p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, weight = 1f))
        if (!hasCenter && !hasRight) {
            if (onLong != null) bar.setOnLongClickListener { onLong(); true }
            return vertical().apply {
                addView(bar)
                addView(rule())
            }
        }
        if (hasCenter) {
            bar.addView(mono(center, 12f, p.text).apply { gravity = Gravity.CENTER }, lp(0, weight = 1f))
        }
        if (rightIcon != null) {
            // 24dp glyph inside a 48dp touch target, tinted to the ink.
            val slot = android.widget.FrameLayout(context)
            val icon = android.widget.ImageView(context).apply {
                setImageResource(rightIcon)
                imageTintList = android.content.res.ColorStateList.valueOf(p.text)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = rightIconLabel
                if (onRight != null) tappable(this, onRight)
            }
            slot.addView(icon, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
            bar.addView(slot, if (hasCenter) lp(0, weight = 1f) else lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else if (right.isNotEmpty()) {
            val r = mono(right, 12f, p.text).apply { gravity = Gravity.END }
            if (onRight != null) {
                r.setPadding(dp(8), dp(6), 0, dp(6))
                tappable(r, onRight)
            }
            bar.addView(r, if (hasCenter) lp(0, weight = 1f) else lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (onLong != null) bar.setOnLongClickListener { onLong(); true }
        return vertical().apply {
            addView(bar)
            addView(rule())
        }
    }

    /** The mono tap bar at the bottom: equal cells separated by rules. */
    fun navBar(vararg items: Pair<String, () -> Unit>): LinearLayout = vertical().apply {
        addView(rule())
        val row = horizontal()
        items.forEachIndexed { i, (label, action) ->
            if (i > 0) row.addView(View(context).apply { setBackgroundColor(p.rule) }, lp(maxOf(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT))
            val cell = mono(label, 12f, p.text).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            }
            tappable(cell, action)
            row.addView(cell, lp(0, weight = 1f))
        }
        addView(row)
    }

    /** Bordered, full-width action: the ink design's primary button. */
    fun boxButton(label: String, onClick: () -> Unit): TextView = text(label.uppercase(), 13f, p.text, Fonts.sansBold).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), box(p.pressed))
            addState(intArrayOf(), box(0))
        }
        setOnClickListener { onClick() }
    }

    fun box(fillColor: Int, dashed: Boolean = false): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        if (dashed) setStroke(dp(1.5f), p.text, dp(5).toFloat(), dp(4).toFloat()) else setStroke(dp(1.5f), p.text)
    }

    /** A settings/action row: label, optional value or LATER tag, soft rule below. */
    fun row(label: String, value: String? = null, warn: Boolean = false, off: Boolean = false, later: Boolean = false, onClick: (() -> Unit)? = null): View {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
        }
        val color = when {
            off || later -> p.soft
            warn -> p.warn
            else -> p.text
        }
        row.addView(text(label, 15f, color).apply {
            if (warn && p.isBlack) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        }, lp(0, weight = 1f))
        if (later) {
            row.addView(mono("Later", 9f, p.soft).apply {
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply { setStroke(maxOf(1, dp(1)), p.soft) }
            })
        } else if (value != null) {
            row.addView(mono(value, 11f, p.soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }
        if (!later && !off) tappable(row, onClick)
        return vertical().apply {
            addView(row)
            addView(rule(soft = true))
        }
    }

    /** Section header inside a list: mono label with a rule below. */
    fun group(label: String): View = vertical().apply {
        addView(mono(label, 10f).apply { setPadding(dp(18), dp(18), dp(18), dp(6)) })
        addView(rule())
    }

    /** Two-way segmented toggle, e.g. COLOR INK | BLACK INK. */
    fun toggle(left: String, right: String, leftOn: Boolean, onLeft: () -> Unit, onRight: () -> Unit): View {
        val row = horizontal()
        row.background = box(0)
        row.setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f))
        fun cell(label: String, on: Boolean, action: () -> Unit) = mono(label, 11f, if (on) p.bg else p.text).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            if (on) setBackgroundColor(p.text) else tappable(this, action)
        }
        row.addView(cell(left, leftOn, onLeft), lp(0, weight = 1f))
        row.addView(cell(right, !leftOn, onRight), lp(0, weight = 1f))
        return row
    }

    fun space(): View = View(context)
}
