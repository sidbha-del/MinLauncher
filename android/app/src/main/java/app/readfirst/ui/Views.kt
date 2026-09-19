package app.readfirst.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import app.readfirst.data.BookEntry
import app.readfirst.data.Library
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Ink-style progress: a row of outlined segments, filled up to the fraction read. */
class SegmentBar(context: Context, private val p: InkPalette, var fraction: Float, private val segments: Int = 20) : View(context) {
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, d); color = p.text }
    private val fill = Paint().apply { color = p.fill }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (8 * d).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val gap = 2.5f * d
        val segW = (width - gap * (segments - 1)) / segments
        val filled = if (fraction <= 0f) 0 else max(1, (fraction * segments).roundToInt())
        for (i in 0 until segments) {
            val x = i * (segW + gap)
            val r = RectF(x + stroke.strokeWidth / 2, stroke.strokeWidth / 2, x + segW - stroke.strokeWidth / 2, height - stroke.strokeWidth / 2)
            if (i < filled) canvas.drawRect(x, 0f, x + segW, height.toFloat(), fill) else canvas.drawRect(r, stroke)
        }
    }
}

/** Book covers from the cover cache, loaded off the main thread. */
object Covers {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun load(context: Context, book: BookEntry, onReady: (Bitmap) -> Unit) {
        if (!book.hasCover) return
        synchronized(cache) { cache.get(book.id) }?.let { onReady(it); return }
        val file = Library.get(context).coverFile(book.id)
        worker.execute {
            val bmp = BitmapFactory.decodeFile(file.path) ?: return@execute
            synchronized(cache) { cache.put(book.id, bmp) }
            main.post { onReady(bmp) }
        }
    }

    fun forget(id: String) = synchronized(cache) { cache.remove(id) }

    /** A stable, muted colour for books without a cover. */
    fun fallbackColor(seed: String): Int {
        val palette = intArrayOf(0xFF2F4A3C.toInt(), 0xFF6B2F2F.toInt(), 0xFF233B5C.toInt(), 0xFF8A6A2C.toInt(), 0xFF4A3B5E.toInt(), 0xFF2B5A5A.toInt(), 0xFF5E4A3B.toInt())
        return palette[abs(seed.hashCode()) % palette.size]
    }
}

/** A small book cover: the real cover image, or a typographic one in the book's colour. */
class CoverView(context: Context, private val p: InkPalette, private val book: BookEntry) : View(context) {
    private var bitmap: Bitmap? = null
    private val d = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = p.imageFilter }
    private val bg = Paint().apply { color = if (book.spineColor != 0) book.spineColor else Covers.fallbackColor(book.title); colorFilter = p.imageFilter }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Fonts.serifBold }

    init {
        Covers.load(context, book) { bitmap = it; invalidate() }
        contentDescription = book.title
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bmp = bitmap
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, w, h), imagePaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, bg)
            titlePaint.textSize = max(7f * d, w / 6f)
            val pad = w * 0.1f
            val layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length, titlePaint, (w - 2 * pad).toInt().coerceAtLeast(1))
                .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
            canvas.save()
            canvas.translate(pad, h - pad - layout.height)
            layout.draw(canvas)
            canvas.restore()
        }
        if (p.isBlack) {
            val outline = Paint().apply { style = Paint.Style.STROKE; color = p.text; strokeWidth = max(1f, d) }
            canvas.drawRect(outline.strokeWidth / 2, outline.strokeWidth / 2, w - outline.strokeWidth / 2, h - outline.strokeWidth / 2, outline)
        }
    }
}

/**
 * One book spine on a shelf. Height varies per title so a shelf looks like real books. In black
 * ink, spines alternate solid and outlined so neighbours stay distinct without colour.
 */
class SpineView(
    context: Context,
    private val p: InkPalette,
    val book: BookEntry,
    private val solid: Boolean,
    private val ribbon: Boolean,
) : View(context) {
    private val d = resources.displayMetrics.density
    /** Thicker for longer books, with a cap so a brick doesn't dominate the shelf. */
    val spineWidth = ((24 + 30 * bookThickness(book)) * d).roundToInt()
    val spineHeight = ((96 + abs(book.title.hashCode()) % 34) * d).roundToInt()
    private val baseColor = if (book.spineColor != 0) book.spineColor else Covers.fallbackColor(book.title)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (p.isBlack) (if (solid) p.text else p.bg) else baseColor
        if (!p.isBlack) colorFilter = p.imageFilter
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = p.text }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            !p.isBlack -> Color.WHITE
            solid -> p.bg
            else -> p.text
        }
        typeface = Fonts.serifBold
        textSize = 10.5f * d
        letterSpacing = 0.06f
    }
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (p.isBlack) p.bg else p.fill }

    init {
        contentDescription = book.title
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(spineWidth, spineHeight)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, body)
        if (p.isBlack && !solid) canvas.drawRect(edge.strokeWidth / 2, edge.strokeWidth / 2, w - edge.strokeWidth / 2, h, edge)
        if (isPressed) canvas.drawRect(0f, 0f, w, h, Paint().apply { color = p.pressed })
        // Title runs bottom-to-top, like a real spine, centred in the space below any ribbon.
        // Thick spines get a second line, so long titles are cut far less often.
        val top = if (ribbon) 32 * d else 8 * d
        val span = (h - top - 8 * d).toInt().coerceAtLeast(1)
        val lines = if (w >= 44 * d) 2 else 1
        val text = book.title.uppercase()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, label, span)
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.95f).setIncludePad(false).build()
        canvas.save()
        // Rotate so the layout's x runs up the spine; centre it across the spine's thickness.
        canvas.translate((w - layout.height) / 2f, h - 8 * d)
        canvas.rotate(-90f)
        layout.draw(canvas)
        canvas.restore()
        if (ribbon) {
            val rw = 9 * d
            val rh = 26 * d
            val x = w - rw - 4 * d
            val path = Path().apply {
                moveTo(x, 0f); lineTo(x + rw, 0f); lineTo(x + rw, rh); lineTo(x + rw / 2, rh * 0.78f); lineTo(x, rh); close()
            }
            canvas.drawPath(path, ribbonPaint)
            if (p.isBlack) canvas.drawPath(path, edge)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** Lays spines out left to right, wrapping onto new shelves, and draws the shelf boards. */
class ShelfLayout(context: Context, private val p: InkPalette) : ViewGroup(context) {
    private val d = resources.displayMetrics.density
    private val gap = (5 * d).roundToInt()
    private val board = (3 * d).roundToInt()
    private val rowGap = (16 * d).roundToInt()
    private val boardPaint = Paint().apply { color = p.text }
    private val rows = ArrayList<Int>() // y of each board's top edge

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var x = paddingLeft
        var rowHeight = 0
        var y = paddingTop
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            if (x + c.measuredWidth > width - paddingRight && x > paddingLeft) {
                y += rowHeight + board + rowGap
                x = paddingLeft
                rowHeight = 0
            }
            x += c.measuredWidth + gap
            rowHeight = max(rowHeight, c.measuredHeight)
        }
        y += rowHeight + board
        setMeasuredDimension(width, y + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        rows.clear()
        val width = r - l
        // First pass: group children into rows so each row can be bottom-aligned.
        val groups = ArrayList<MutableList<View>>()
        var x = paddingLeft
        var current = ArrayList<View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x + c.measuredWidth > width - paddingRight && current.isNotEmpty()) {
                groups += current
                current = ArrayList()
                x = paddingLeft
            }
            current += c
            x += c.measuredWidth + gap
        }
        if (current.isNotEmpty()) groups += current
        var y = paddingTop
        for (g in groups) {
            val h = g.maxOf { it.measuredHeight }
            var cx = paddingLeft
            for (c in g) {
                c.layout(cx, y + h - c.measuredHeight, cx + c.measuredWidth, y + h)
                cx += c.measuredWidth + gap
            }
            rows += y + h
            y += h + board + rowGap
        }
    }

    override fun onDraw(canvas: Canvas) {
        for (top in rows) canvas.drawRect(0f, top.toFloat(), width.toFloat(), (top + board).toFloat(), boardPaint)
    }
}

/** 0..1 from a book's estimated length on a log scale: ~40 pages is thin, ~900+ is as thick as it gets. */
fun bookThickness(book: BookEntry): Float {
    val pages = book.pagesEstimate
    if (pages <= 0) return 0.35f
    return (kotlin.math.ln(pages / 40f) / kotlin.math.ln(900f / 40f)).coerceIn(0f, 1f)
}

/**
 * A book lying flat in a stack: the title reads left to right across the full width, so long
 * titles fit. Thickness follows the book's length; each book sits slightly offset like a real
 * pile. Black ink alternates solid and outlined, like the spines.
 */
class StackBookView(
    context: Context,
    private val p: InkPalette,
    val book: BookEntry,
    private val solid: Boolean,
    private val ribbon: Boolean,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val length = bookThickness(book)
    private val thickness = ((30 + 26 * length) * d).roundToInt()
    private val hash = abs(book.title.hashCode())
    /** Longer books are a little wider too, so both dimensions read as length. */
    private val widthFraction = 0.80f + 0.20f * length
    /** A pile never lines up perfectly: a small, stable sideways offset per book. */
    private val offset = (hash % 13) * d

    private val baseColor = if (book.spineColor != 0) book.spineColor else Covers.fallbackColor(book.title)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (p.isBlack) (if (solid) p.text else p.bg) else baseColor
        if (!p.isBlack) colorFilter = p.imageFilter
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = p.text }
    private val ink = when {
        !p.isBlack -> Color.WHITE
        solid -> p.bg
        else -> p.text
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Fonts.serifBold; textSize = 13.5f * d }
    private val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 190; typeface = Fonts.mono; textSize = 9.5f * d; letterSpacing = 0.06f }
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (p.isBlack) p.bg else p.fill }

    init {
        contentDescription = book.title
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), thickness)

    override fun onDraw(canvas: Canvas) {
        val left = offset
        val right = left + (width - 13 * d) * widthFraction
        val h = height.toFloat()
        canvas.drawRect(left, 0f, right, h, body)
        if (p.isBlack && !solid) canvas.drawRect(left + edge.strokeWidth / 2, edge.strokeWidth / 2, right - edge.strokeWidth / 2, h - edge.strokeWidth / 2, edge)
        if (isPressed) canvas.drawRect(left, 0f, right, h, Paint().apply { color = p.pressed })

        val pad = 12 * d
        val ribbonSpace = if (ribbon) 22 * d else 0f
        val author = book.author.substringBefore(',').uppercase()
        val authorW = if (author.isNotBlank() && right - left > 220 * d) authorPaint.measureText(author).coerceAtMost((right - left) * 0.35f) else 0f
        val titleW = (right - left - 2 * pad - ribbonSpace - (if (authorW > 0) authorW + pad else 0f)).coerceAtLeast(1f)
        val lines = if (h >= 46 * d) 2 else 1
        val layout = StaticLayout.Builder.obtain(book.title, 0, book.title.length, titlePaint, titleW.toInt())
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 0.95f).setIncludePad(false).build()
        canvas.save()
        canvas.translate(left + pad, (h - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        if (authorW > 0) {
            val a = TextUtils.ellipsize(author, authorPaint, authorW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(a, right - pad - ribbonSpace - authorPaint.measureText(a), h / 2f + authorPaint.textSize / 3f, authorPaint)
        }
        if (ribbon) {
            val rw = 9 * d
            val x = right - rw - 8 * d
            val path = Path().apply {
                moveTo(x, 0f); lineTo(x + rw, 0f); lineTo(x + rw, h); lineTo(x + rw / 2, h - 6 * d); lineTo(x, h); close()
            }
            canvas.drawPath(path, ribbonPaint)
            if (p.isBlack) canvas.drawPath(path, edge)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
