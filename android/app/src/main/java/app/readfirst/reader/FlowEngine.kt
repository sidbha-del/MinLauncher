package app.readfirst.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import app.readfirst.book.ChapterText
import app.readfirst.book.FlowBook
import app.readfirst.book.Locator
import java.util.concurrent.Executors

/**
 * Lays chapters out with StaticLayout and slices them into screen-sized pages. Pages are ranges
 * of lines, positions are character offsets, so a change of font size or margin re-paginates
 * without losing the reader's place.
 *
 * Layout work runs off the main thread; results come back on the main thread. A second worker
 * counts pages across the whole book so "p. 142/380" can be shown once it finishes.
 */
class FlowEngine(val book: FlowBook, val params: Params) {

    data class Params(val width: Int, val height: Int, val textPx: Float, val serif: Boolean, val density: Float)

    class ChapterLayout(val index: Int, val layout: StaticLayout, val pageStarts: IntArray) {
        val pageCount get() = pageStarts.size

        fun pageForOffset(offset: Int): Int {
            val line = layout.getLineForOffset(offset.coerceIn(0, layout.text.length))
            var page = 0
            for (i in pageStarts.indices) if (pageStarts[i] <= line) page = i
            return page
        }

        fun offsetForPage(page: Int): Int = layout.getLineStart(pageStarts[page.coerceIn(0, pageStarts.size - 1)])

        fun lineRange(page: Int): IntRange {
            val p = page.coerceIn(0, pageStarts.size - 1)
            val end = if (p + 1 < pageStarts.size) pageStarts[p + 1] else layout.lineCount
            return pageStarts[p] until end
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val layoutWorker = Executors.newSingleThreadExecutor()
    private val countWorker = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<Int, ChapterLayout>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterLayout>?) = size > 3
    }
    private val pending = HashMap<Int, MutableList<(ChapterLayout) -> Unit>>()
    @Volatile
    private var closed = false

    /** Pages per chapter once the whole book has been counted, else null. */
    @Volatile
    var pageCounts: IntArray? = null
        private set

    fun cached(chapter: Int): ChapterLayout? = synchronized(cache) { cache[chapter] }

    /** Delivers the chapter's layout on the main thread, building it in the background if needed. */
    fun get(chapter: Int, onReady: (ChapterLayout) -> Unit) {
        cached(chapter)?.let { onReady(it); return }
        val waiters = pending[chapter]
        if (waiters != null) {
            waiters += onReady
            return
        }
        pending[chapter] = mutableListOf(onReady)
        layoutWorker.execute {
            if (closed) return@execute
            val cl = build(chapter)
            synchronized(cache) { cache[chapter] = cl }
            main.post {
                val list = pending.remove(chapter).orEmpty()
                if (!closed) list.forEach { it(cl) }
            }
        }
    }

    fun prefetch(chapter: Int) {
        if (chapter !in book.chapters.indices || cached(chapter) != null || pending.containsKey(chapter)) return
        get(chapter) {}
    }

    /** Counts every chapter's pages in the background, then calls [onDone] on the main thread. */
    fun countAll(onDone: () -> Unit) {
        countWorker.execute {
            val counts = IntArray(book.chapters.size)
            for (i in book.chapters.indices) {
                if (closed) return@execute
                counts[i] = cached(i)?.pageCount ?: build(i).pageCount
            }
            pageCounts = counts
            main.post { if (!closed) onDone() }
        }
    }

    fun globalPage(at: Locator, pageInChapter: Int): Pair<Int, Int>? {
        val counts = pageCounts ?: return null
        var before = 0
        for (i in 0 until at.chapter.coerceAtMost(counts.size)) before += counts[i]
        return (before + pageInChapter) to counts.sum()
    }

    fun chapterStartPage(chapter: Int): Int? {
        val counts = pageCounts ?: return null
        var n = 0
        for (i in 0 until chapter.coerceAtMost(counts.size)) n += counts[i]
        return n
    }

    fun close() {
        closed = true
        layoutWorker.shutdownNow()
        countWorker.shutdownNow()
    }

    // ---- layout -------------------------------------------------------------------------------

    private fun build(chapter: Int): ChapterLayout {
        val ct = ChapterText.build(book.chapters[chapter])
        val text = styled(ct)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = params.textPx
            typeface = if (params.serif) Typeface.SERIF else Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, params.width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.4f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            .build()
        return ChapterLayout(chapter, layout, paginate(layout, params.height))
    }

    private fun styled(ct: ChapterText): SpannableString {
        val s = SpannableString(ct.text)
        val d = params.density
        val paraGap = (params.textPx * 0.55f).toInt()
        for (r in ct.ranges) {
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            when (r.style) {
                ChapterText.Style.BOLD -> s.setSpan(StyleSpan(Typeface.BOLD), r.start, r.end, flags)
                ChapterText.Style.ITALIC -> s.setSpan(StyleSpan(Typeface.ITALIC), r.start, r.end, flags)
                ChapterText.Style.HEADING -> {
                    val scale = when (r.level) { 1 -> 1.45f; 2 -> 1.3f; 3 -> 1.15f; else -> 1.05f }
                    s.setSpan(RelativeSizeSpan(scale), r.start, r.end, flags)
                    s.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), r.start, r.end, flags)
                    s.setSpan(SpaceBefore((params.textPx * 1.2f).toInt()), r.start, r.end, flags)
                }
                ChapterText.Style.QUOTE -> s.setSpan(LeadingMarginSpan.Standard((20 * d).toInt()), r.start, r.end, flags)
                ChapterText.Style.LIST_ITEM -> {
                    val hang = (params.textPx * 1.4f).toInt()
                    s.setSpan(LeadingMarginSpan.Standard((8 * d).toInt(), (8 * d).toInt() + hang), r.start, r.end, flags)
                }
                ChapterText.Style.PREFORMATTED -> {
                    s.setSpan(TypefaceSpan("monospace"), r.start, r.end, flags)
                    s.setSpan(RelativeSizeSpan(0.85f), r.start, r.end, flags)
                }
                ChapterText.Style.PARAGRAPH -> s.setSpan(SpaceAfter(paraGap), r.start, r.end, flags)
            }
        }
        return s
    }

    /** Slices a chapter into pages: each page is the lines that fit within [height]. */
    private fun paginate(layout: StaticLayout, height: Int): IntArray {
        val starts = ArrayList<Int>()
        var line = 0
        while (line < layout.lineCount) {
            starts += line
            val top = layout.getLineTop(line)
            var next = line + 1
            while (next < layout.lineCount && layout.getLineBottom(next) - top <= height) next++
            line = next
        }
        if (starts.isEmpty()) starts += 0
        return starts.toIntArray()
    }

    /** Extra space below the last line of a paragraph. */
    private class SpaceAfter(private val px: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (end >= (text as Spanned).getSpanEnd(this)) {
                fm.descent += px
                fm.bottom += px
            }
        }
    }

    /** Extra space above the first line of a heading. */
    private class SpaceBefore(private val px: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (start == (text as Spanned).getSpanStart(this) && start > 0) {
                fm.ascent -= px
                fm.top -= px
            }
        }
    }
}
