package app.booklauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import app.booklauncher.data.Page
import app.booklauncher.data.PageStyle
import app.booklauncher.data.Texture
import kotlin.random.Random

/**
 * Page backgrounds: the page colour plus an optional texture. Textures are generated tiles
 * (no image assets), faint enough to read over, drawn darker on light pages and lighter on
 * dark ones.
 */
object PageLook {
    private const val TILE = 192
    private val cache = HashMap<String, Bitmap>()

    fun background(context: Context, p: InkPalette): Drawable {
        val color = ColorDrawable(p.bg)
        val tile = tile(p.style.texture, p.dark) ?: return color
        val tex = BitmapDrawable(context.resources, tile).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        return LayerDrawable(arrayOf(color, tex))
    }

    private fun tile(texture: Texture, dark: Boolean): Bitmap? {
        if (texture == Texture.NONE) return null
        val key = "$texture/$dark"
        synchronized(cache) { cache[key]?.let { return it } }
        val rnd = Random(texture.ordinal * 7919 + 17)
        val pixels = IntArray(TILE * TILE)
        val base = if (dark) 0xFFFFFF else 0x2A2218
        val maxAlpha = if (dark) 14 else 20
        when (texture) {
            Texture.GRAIN -> for (i in pixels.indices) {
                val a = (rnd.nextFloat() * rnd.nextFloat() * maxAlpha).toInt()
                pixels[i] = (a shl 24) or base
            }
            Texture.LINEN -> {
                // Woven look: per-row and per-column thread strength, plus a little grain.
                val rows = FloatArray(TILE) { rnd.nextFloat() }
                val cols = FloatArray(TILE) { rnd.nextFloat() }
                for (y in 0 until TILE) for (x in 0 until TILE) {
                    val thread = if ((x + y) % 2 == 0) rows[y] else cols[x]
                    val a = ((thread * 0.75f + rnd.nextFloat() * 0.25f) * maxAlpha).toInt()
                    pixels[y * TILE + x] = (a shl 24) or base
                }
            }
            Texture.NONE -> {}
        }
        val bmp = Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888)
        bmp.density = Bitmap.DENSITY_NONE
        synchronized(cache) { cache[key] = bmp }
        return bmp
    }

    /**
     * Page chooser: colour swatches and texture chips. Used in Settings (launcher page, books'
     * own page) and in the reader's Aa menu.
     */
    fun chooser(ui: Ui, current: PageStyle, onChange: (PageStyle) -> Unit): View {
        val p = ui.p
        val col = ui.vertical()
        val swatches = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        for (page in Page.values()) {
            val on = page == current.page
            val cell = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val dot = View(ui.context).apply {
                background = swatch(ui, page, on)
                contentDescription = page.label + if (on) ", selected" else ""
            }
            cell.addView(dot, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
            cell.addView(ui.mono(page.label, 9f, if (on) p.text else p.soft).apply { gravity = Gravity.CENTER }.also { ui.margins(it, t = 5) })
            ui.tappable(cell, { if (!on) onChange(current.copy(page = page)) })
            swatches.addView(cell, ui.lp(0, weight = 1f))
        }
        col.addView(swatches)
        val chips = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        // Chooser sits 6dp further left than list text (for the swatch row); pad the label back into line.
        chips.addView(ui.mono("Texture", 10f).apply { setPadding(ui.dp(6), 0, 0, 0) }, ui.lp(0, weight = 1f))
        for (t in Texture.values()) {
            val on = t == current.texture
            val chip = ui.mono(t.label, 10.5f, if (on) p.bg else p.text).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.box(if (on) p.text else 0)
                if (!on) setOnClickListener { onChange(current.copy(texture = t)) }
            }
            chips.addView(chip, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(8) })
        }
        col.addView(chips)
        ui.margins(chips, t = 14)
        return col
    }

    private fun swatch(ui: Ui, page: Page, selected: Boolean): Drawable {
        val fill = if (page == Page.DEFAULT) (if (ui.p.isBlack) Page.WHITE.color else Page.PAPER.color) else page.color
        val g = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(ui.dp(if (selected) 3 else 1), ui.p.text)
        }
        if (page != Page.DEFAULT) return g
        // "Default" is marked with a small inner ring so it reads as "the ink's own page".
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0)
            setStroke(ui.dp(1), ui.p.soft)
        }
        return LayerDrawable(arrayOf(g, inner)).apply {
            val inset = ui.dp(9)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
}
