package app.readfirst.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/** A ScrollView that is only as tall as its content, up to [fraction] of the screen. */
class CappedScroll(context: Context, private val fraction: Float) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * fraction).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/**
 * A bottom sheet over the current screen: a dimmed backdrop (tap to close) and [content] rising
 * from the bottom edge. Slides in Color ink; appears instantly in Black ink. Returns the overlay
 * so the caller can remove it.
 */
fun attachSheet(root: FrameLayout, ui: Ui, content: View, onBackdrop: () -> Unit): View {
    val ctx = root.context
    val overlay = FrameLayout(ctx)
    val dim = View(ctx).apply {
        // The page colour, mostly opaque: the screen behind fades back rather than going grey.
        setBackgroundColor((ui.p.bg and 0x00FFFFFF) or (if (ui.p.isBlack) 0xB3000000.toInt() else 0x99000000.toInt()))
        setOnClickListener { onBackdrop() }
    }
    overlay.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val holder = ui.vertical().apply {
        background = PageLook.background(ctx, ui.p)
        addView(ui.rule(thicknessDp = 1.5f))
        addView(content)
        isClickable = true
    }
    overlay.addView(holder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    if (ui.p.animate) {
        holder.translationY = 400 * ui.density
        holder.animate().translationY(0f).setDuration(170).start()
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(170).start()
    }
    return overlay
}
