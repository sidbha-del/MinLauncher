package app.booklauncher.reader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.booklauncher.HomeActivity
import app.booklauncher.book.Epub
import app.booklauncher.book.FlowBook
import app.booklauncher.book.Format
import app.booklauncher.book.Locator
import app.booklauncher.book.Progress
import app.booklauncher.book.TextBook
import app.booklauncher.data.BookEntry
import app.booklauncher.data.Ink
import app.booklauncher.data.Library
import app.booklauncher.data.Prefs
import app.booklauncher.data.Shelf
import app.booklauncher.ui.CappedScroll
import app.booklauncher.ui.Fonts
import app.booklauncher.ui.InkPalette
import app.booklauncher.ui.PageLook
import app.booklauncher.ui.Ui
import app.booklauncher.ui.attachSheet
import app.booklauncher.ui.setBarIcons
import java.util.concurrent.Executors

/**
 * Reading: TXT and EPUB through [FlowEngine], PDF through [PdfDoc]. Tap left/right thirds or
 * swipe to turn pages, tap the centre (or press Menu) for the Aa menu, volume keys also turn
 * pages. The position is saved to the library after every page turn.
 */
class ReaderActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var library: Library
    private lateinit var entry: BookEntry
    private lateinit var ui: Ui

    private lateinit var root: FrameLayout
    private lateinit var header: TextView
    private lateinit var footLeft: TextView
    private lateinit var footRight: TextView
    private lateinit var pageHost: FrameLayout
    private var sheet: View? = null
    private val pager by lazy { TouchPager(this, ::next, ::prev, ::toggleMenu) }

    // Reflowable state
    private var flow: FlowBook? = null
    private var engine: FlowEngine? = null
    private var pageView: PageView? = null
    private var chapterLayout: FlowEngine.ChapterLayout? = null
    private var locator = Locator.START
    private var pageInChapter = 0

    // PDF state
    private var pdf: PdfDoc? = null
    private var pdfView: ImageView? = null
    private var pdfPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        library = Library.get(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val found = id?.let { library.find(it) }
        if (found == null) {
            finish()
            return
        }
        entry = found
        ui = Ui(this, InkPalette.of(prefs.ink, prefs.readerPage))
        root = FrameLayout(this)
        setContentView(root)
        applyInsets()
        immersive()
        buildChrome()
        showMessage("Opening…")
        open()
    }

    private val readingTimer by lazy { app.booklauncher.data.Focus.ReadingTimer(this) }

    override fun onResume() {
        super.onResume()
        readingTimer.start()
    }

    override fun onPause() {
        super.onPause()
        readingTimer.stop()
        save()
    }

    override fun onDestroy() {
        engine?.close()
        pdf?.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    // ---- layout -------------------------------------------------------------------------------

    private fun buildChrome() {
        val p = ui.p
        root.removeAllViews()
        root.background = PageLook.background(this, p)
        window.decorView.setBackgroundColor(p.bg)
        setBarIcons(this, p.dark)
        val col = ui.vertical()
        header = ui.mono("", 10f).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(ui.dp(24), ui.dp(14), ui.dp(24), ui.dp(6))
        }
        col.addView(header)
        pageHost = FrameLayout(this)
        col.addView(pageHost, ui.lp(h = 0, weight = 1f))
        col.addView(ui.rule())
        val foot = ui.horizontal().apply { setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(12)) }
        footLeft = ui.mono("", 10f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        footRight = ui.mono("", 10f).apply { gravity = Gravity.END }
        foot.addView(footLeft, ui.lp(0, weight = 1f))
        foot.addView(footRight, ui.lp().apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; leftMargin = ui.dp(12) })
        col.addView(foot)
        root.addView(col)
        footLeft.text = entry.title.uppercase()
    }

    private fun showMessage(text: String, actions: List<Pair<String, () -> Unit>> = emptyList()) {
        pageHost.removeAllViews()
        val box = ui.vertical().apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(28), 0, ui.dp(28), 0)
        }
        box.addView(ui.serif(text, 17f).apply { gravity = Gravity.CENTER })
        for ((label, action) in actions) box.addView(ui.boxButton(label, action).also { ui.margins(it, t = 14) })
        pageHost.addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun applyInsets() {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            }
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // ---- opening ------------------------------------------------------------------------------

    private fun open() {
        val book = entry
        loader.execute {
            val result = runCatching {
                when (book.format) {
                    Format.PDF -> PdfDoc.open(this, Uri.parse(book.uri))
                    Format.EPUB, Format.TXT -> BookCache.load(this, book)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    (result.getOrNull() as? PdfDoc)?.close()
                    return@runOnUiThread
                }
                result.onSuccess {
                    when (it) {
                        is PdfDoc -> startPdf(it)
                        is FlowBook -> startFlow(it)
                    }
                    markOpened()
                }.onFailure { showOpenError() }
            }
        }
    }

    private fun showOpenError() {
        showMessage(
            "This book can't be opened. It may have been moved, deleted, or isn't a readable file.",
            listOf(
                "Remove from library" to { library.remove(entry.id); finish() },
                "Back" to { finish() },
            ),
        )
    }

    private fun markOpened() {
        library.update(entry.id) {
            it.copy(openedAt = System.currentTimeMillis(), shelf = if (it.shelf == Shelf.TO_READ) Shelf.READING else it.shelf)
        }
    }

    // ---- reflowable ---------------------------------------------------------------------------

    private fun startFlow(book: FlowBook) {
        flow = book
        locator = Locator(entry.chapter.coerceIn(0, book.chapters.size - 1), entry.offset)
        pageHost.removeAllViews()
        val pv = PageView(this, pager).apply { textColor = ui.p.text }
        pageView = pv
        pageHost.addView(pv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pv.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) pv.post { relayout() }
        }
    }

    /** Builds a fresh engine for the current view size and settings, keeping the position. */
    private fun relayout() {
        val book = flow ?: return
        val pv = pageView ?: return
        if (pv.width == 0 || pv.height == 0) return
        val side = ui.dp(MARGINS[prefs.margin])
        pv.setPadding(side, ui.dp(8), side, ui.dp(8))
        engine?.close()
        val params = FlowEngine.Params(
            width = pv.width - 2 * side,
            height = pv.height - ui.dp(16),
            textPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, prefs.textSize.toFloat(), resources.displayMetrics),
            serif = prefs.serif,
            density = resources.displayMetrics.density,
        )
        val e = FlowEngine(book, params)
        engine = e
        chapterLayout = null
        e.get(locator.chapter) { cl ->
            if (engine !== e) return@get
            chapterLayout = cl
            pageInChapter = cl.pageForOffset(locator.offset)
            render(animate = false)
            e.countAll { if (engine === e) updateChrome() }
        }
    }

    private fun render(animate: Boolean, forward: Boolean = true) {
        val cl = chapterLayout ?: return
        val pv = pageView ?: return
        pv.show(cl, pageInChapter)
        locator = Locator(cl.index, cl.offsetForPage(pageInChapter))
        if (animate && ui.p.animate) {
            pv.translationX = (if (forward) 28 else -28) * ui.density
            pv.alpha = 0.55f
            pv.animate().translationX(0f).alpha(1f).setDuration(140).start()
        }
        updateChrome()
        engine?.prefetch(cl.index + 1)
        engine?.prefetch(cl.index - 1)
        save()
    }

    private fun goToChapter(chapter: Int, atEnd: Boolean, forward: Boolean) {
        val e = engine ?: return
        e.get(chapter) { cl ->
            if (engine !== e) return@get
            chapterLayout = cl
            pageInChapter = if (atEnd) cl.pageCount - 1 else 0
            render(animate = true, forward = forward)
        }
    }

    private fun jumpTo(target: Locator) {
        val e = engine ?: return
        e.get(target.chapter) { cl ->
            if (engine !== e) return@get
            chapterLayout = cl
            pageInChapter = cl.pageForOffset(target.offset)
            render(animate = false)
        }
    }

    // ---- page turns (both kinds) --------------------------------------------------------------

    private fun next() {
        if (sheet != null) return closeSheet()
        readingTimer.tick()
        pdf?.let {
            if (pdfPage < it.pageCount - 1) showPdfPage(pdfPage + 1, forward = true)
            return
        }
        val cl = chapterLayout ?: return
        val book = flow ?: return
        when {
            pageInChapter < cl.pageCount - 1 -> { pageInChapter++; render(animate = true) }
            cl.index < book.chapters.size - 1 -> goToChapter(cl.index + 1, atEnd = false, forward = true)
        }
    }

    private fun prev() {
        if (sheet != null) return closeSheet()
        readingTimer.tick()
        pdf?.let {
            if (pdfPage > 0) showPdfPage(pdfPage - 1, forward = false)
            return
        }
        val cl = chapterLayout ?: return
        when {
            pageInChapter > 0 -> { pageInChapter--; render(animate = true, forward = false) }
            cl.index > 0 -> goToChapter(cl.index - 1, atEnd = true, forward = false)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { next(); return true }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { prev(); return true }
            KeyEvent.KEYCODE_MENU -> { toggleMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- PDF ----------------------------------------------------------------------------------

    private fun startPdf(doc: PdfDoc) {
        pdf = doc
        pdfPage = entry.chapter.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
        pageHost.removeAllViews()
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { v, e -> pager.onTouch(v, e) }
        }
        pdfView = iv
        applyPdfFilter()
        pageHost.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        iv.post { showPdfPage(pdfPage, forward = true, animate = false) }
    }

    /**
     * PDFs take on the page: white paper becomes the page colour and black print becomes the ink,
     * so Night is dark and Sepia is warm. A plain white page in Color ink keeps the original
     * colours, as does the "Original" option.
     */
    private fun applyPdfFilter() {
        val p = ui.p
        val keep = prefs.pdfOriginalColours || (!p.isBlack && !p.dark && p.bg == android.graphics.Color.WHITE)
        pdfView?.colorFilter = if (keep) null else paperFilter(p.bg, p.text)
    }

    private fun paperFilter(paper: Int, ink: Int): android.graphics.ColorMatrixColorFilter {
        // out = ink - (ink - paper) * luminance: white maps to paper, black to ink.
        fun row(shift: Int): FloatArray {
            val i = ((ink shr shift) and 0xFF).toFloat()
            val k = -(i - ((paper shr shift) and 0xFF)) / 255f
            return floatArrayOf(k * 0.299f, k * 0.587f, k * 0.114f, 0f, i)
        }
        return android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(row(16) + row(8) + row(0) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
    }

    private fun showPdfPage(page: Int, forward: Boolean, animate: Boolean = true) {
        val doc = pdf ?: return
        val iv = pdfView ?: return
        pdfPage = page
        doc.render(page, iv.width.coerceAtLeast(1), iv.height.coerceAtLeast(1), prefs.pdfTrim) { bmp: Bitmap ->
            if (page != pdfPage) return@render
            iv.setImageBitmap(bmp)
            if (animate && ui.p.animate) {
                iv.translationX = (if (forward) 28 else -28) * ui.density
                iv.alpha = 0.55f
                iv.animate().translationX(0f).alpha(1f).setDuration(140).start()
            }
        }
        updateChrome()
        save()
    }

    // ---- chrome and saving --------------------------------------------------------------------

    private fun updateChrome() {
        pdf?.let {
            header.text = "PAGE ${pdfPage + 1}"
            footRight.text = "${pdfPage + 1} / ${it.pageCount}"
            return
        }
        val book = flow ?: return
        val cl = chapterLayout ?: return
        header.text = book.chapters[cl.index].title.uppercase()
        val global = engine?.globalPage(locator, pageInChapter)
        footRight.text = if (global != null) "${global.first + 1} / ${global.second}"
        else "${Progress.percent(Progress.flow(book.chapterLengths, locator))}%"
    }

    private fun save() {
        if (!::entry.isInitialized) return
        pdf?.let { doc ->
            val progress = Progress.paged(pdfPage, doc.pageCount)
            library.update(entry.id) {
                it.copy(
                    chapter = pdfPage, offset = 0, progress = progress, page = pdfPage, pages = doc.pageCount,
                    chapterTitle = "", excerpt = "",
                    shelf = if (pdfPage >= doc.pageCount - 1 && doc.pageCount > 1) Shelf.FINISHED else it.shelf,
                )
            }
            return
        }
        val book = flow ?: return
        val cl = chapterLayout ?: return
        val progress = Progress.flow(book.chapterLengths, locator)
        val global = engine?.globalPage(locator, pageInChapter)
        val atEnd = cl.index == book.chapters.size - 1 && pageInChapter == cl.pageCount - 1
        library.update(entry.id) {
            it.copy(
                chapter = locator.chapter, offset = locator.offset, progress = if (atEnd) 1f else progress,
                excerpt = excerptAt(cl), chapterTitle = book.chapters[cl.index].title,
                page = global?.first ?: 0, pages = global?.second ?: 0,
                shelf = if (atEnd) Shelf.FINISHED else it.shelf,
            )
        }
    }

    /** About three lines' worth of text from the top of the current page, cut at a word. */
    private fun excerptAt(cl: FlowEngine.ChapterLayout): String {
        val text = cl.layout.text
        val start = locator.offset.coerceIn(0, text.length)
        val end = (start + 280).coerceAtMost(text.length)
        var s = text.subSequence(start, end).toString().replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (end < text.length) s = s.substringBeforeLast(' ', s)
        return s
    }

    // ---- Aa menu ------------------------------------------------------------------------------

    private fun toggleMenu() {
        if (sheet != null) closeSheet() else openMenu()
    }

    private fun closeSheet() {
        sheet?.let { root.removeView(it) }
        sheet = null
    }

    private fun showSheet(content: View) {
        closeSheet()
        sheet = attachSheet(root, ui, content) { closeSheet() }
    }

    private fun openMenu() {
        val p = ui.p
        val col = ui.vertical().apply { setPadding(0, 0, 0, 0) }
        val status = ui.mono(footRight.text.toString().let { pages ->
            val book = flow
            if (book != null) "${header.text} · $pages · ${Progress.percent(Progress.flow(book.chapterLengths, locator))}%"
            else "PAGE $pages"
        }, 10f)
        col.addView(status.also { ui.margins(it, 18, 14, 18, 4) })

        val seek = SeekBar(this).apply {
            max = 1000
            progress = (currentFraction() * 1000).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(p.fill)
            thumbTintList = android.content.res.ColorStateList.valueOf(p.fill)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(p.ruleSoft)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = seekTo(s.progress / 1000f)
            })
        }
        col.addView(seek)
        ui.margins(seek, 6, 4, 6, 6)

        if (flow != null) {
            col.addView(stepper("Text", "${prefs.textSize}", { changeText(-1) }, { changeText(+1) }))
            col.addView(choice("Font", listOf("Serif" to prefs.serif, "Sans" to !prefs.serif)) { i ->
                prefs.serif = i == 0
                relayoutAndReopenMenu()
            })
            col.addView(stepper("Margins", listOf("S", "M", "L", "XL")[prefs.margin], { changeMargin(-1) }, { changeMargin(+1) }))
        } else if (pdf != null) {
            // PDF pages are fixed; what we can change is how much of the page fills the screen and its colours.
            col.addView(choice("Margins", listOf("Trim" to prefs.pdfTrim, "Full page" to !prefs.pdfTrim), sample = false) { i ->
                prefs.pdfTrim = i == 0
                showPdfPage(pdfPage, forward = true, animate = false)
                openMenu()
            })
            col.addView(choice("Colours", listOf("Page" to !prefs.pdfOriginalColours, "Original" to prefs.pdfOriginalColours), sample = false) { i ->
                prefs.pdfOriginalColours = i == 1
                applyPdfFilter()
                openMenu()
            })
        }
        col.addView(ui.toggle("Color ink", "Black ink", prefs.ink == Ink.COLOR, { setInk(Ink.COLOR) }, { setInk(Ink.BLACK) }).also {
            ui.margins(it, 18, 12, 18, 12)
        })
        col.addView(ui.mono(if (prefs.booksShareLauncherPage) "Page · shared with launcher" else "Page · books only", 10f).also {
            ui.margins(it, 18, 4, 18, 10)
        })
        col.addView(PageLook.chooser(ui, prefs.readerPage) { style ->
            prefs.setReaderPage(style)
            restyle()
        }.also { ui.margins(it, 12, 0, 18, 14) })
        val nav = if (flow != null) ui.navBar("Contents" to { openContents() }, "Home" to { goHome() })
        else ui.navBar("Home" to { goHome() })
        val sheetBody = ui.vertical()
        sheetBody.addView(CappedScroll(this, 0.62f).apply { addView(col) })
        sheetBody.addView(nav)
        showSheet(sheetBody)
    }

    private fun stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit): View {
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
        }
        row.addView(ui.mono(label, 11f, ui.p.text), ui.lp(0, weight = 1f))
        row.addView(smallBox("−", minus))
        row.addView(ui.mono(value, 12f, ui.p.text).apply { gravity = Gravity.CENTER }, ui.lp(ui.dp(52)))
        row.addView(smallBox("+", plus))
        return row
    }

    /** [sample]: show each option in its own typeface (Serif / Sans), else plain labels. */
    private fun choice(label: String, options: List<Pair<String, Boolean>>, sample: Boolean = true, onPick: (Int) -> Unit): View {
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
        }
        row.addView(ui.mono(label, 11f, ui.p.text), ui.lp(0, weight = 1f))
        options.forEachIndexed { i, (name, on) ->
            val b = ui.text(name, 14f, if (on) ui.p.bg else ui.p.text, if (sample && i == 0) Fonts.serif else Fonts.sans).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7))
                background = ui.box(if (on) ui.p.text else 0)
                if (!on) setOnClickListener { onPick(i) }
            }
            row.addView(b, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(8) })
        }
        return row
    }

    private fun smallBox(label: String, action: () -> Unit): TextView = ui.mono(label, 14f, ui.p.text).apply {
        gravity = Gravity.CENTER
        background = ui.box(0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(36))
    }

    private fun changeText(delta: Int) {
        val v = (prefs.textSize + delta * 2).coerceIn(Prefs.MIN_TEXT, Prefs.MAX_TEXT)
        if (v == prefs.textSize) return
        prefs.textSize = v
        relayoutAndReopenMenu()
    }

    private fun changeMargin(delta: Int) {
        val v = (prefs.margin + delta).coerceIn(0, MARGINS.size - 1)
        if (v == prefs.margin) return
        prefs.margin = v
        relayoutAndReopenMenu()
    }

    private fun relayoutAndReopenMenu() {
        relayout()
        openMenu()
    }

    private fun setInk(ink: Ink) {
        prefs.ink = ink
        restyle()
    }

    /** Re-applies ink and page to the open book without reloading or losing the place. */
    private fun restyle() {
        ui = Ui(this, InkPalette.of(prefs.ink, prefs.readerPage))
        val keepFlow = flow != null
        closeSheet()
        val oldPage = pageView
        val oldPdf = pdfView
        buildChrome()
        if (keepFlow && oldPage != null) {
            oldPage.textColor = ui.p.text
            (oldPage.parent as? FrameLayout)?.removeView(oldPage)
            pageHost.addView(oldPage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            oldPage.invalidate()
        } else if (oldPdf != null) {
            (oldPdf.parent as? FrameLayout)?.removeView(oldPdf)
            pageHost.addView(oldPdf, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            applyPdfFilter()
        }
        updateChrome()
        openMenu()
    }

    private fun currentFraction(): Float {
        pdf?.let { return Progress.paged(pdfPage, it.pageCount) }
        val book = flow ?: return 0f
        return Progress.flow(book.chapterLengths, locator)
    }

    private fun seekTo(fraction: Float) {
        pdf?.let {
            showPdfPage((fraction * (it.pageCount - 1)).toInt().coerceIn(0, it.pageCount - 1), forward = true, animate = false)
            return
        }
        val book = flow ?: return
        val lengths = book.chapterLengths
        var remaining = (fraction * lengths.sum()).toLong()
        for (i in lengths.indices) {
            if (remaining <= lengths[i] || i == lengths.size - 1) {
                jumpTo(Locator(i, remaining.toInt().coerceIn(0, lengths[i])))
                return
            }
            remaining -= lengths[i]
        }
    }

    private fun openContents() {
        val book = flow ?: return
        val e = engine
        val col = ui.vertical()
        val list = ui.vertical()
        val current = chapterLayout?.index ?: locator.chapter
        var currentRow: View? = null
        var before = 0L
        val total = book.chapterLengths.sum().coerceAtLeast(1)
        book.chapters.forEachIndexed { i, ch ->
            val page = e?.chapterStartPage(i)?.let { "${it + 1}" } ?: "${(before * 100 / total)}%"
            before += book.chapterLengths[i]
            val row = ui.horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13))
            }
            val name = (if (i == current) "▸ " else "") + ch.title
            row.addView(ui.serif(name, 15.5f, bold = i == current).apply { maxLines = 2 }, ui.lp(0, weight = 1f))
            row.addView(ui.mono(page, 11f), ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(12) })
            ui.tappable(row, {
                closeSheet()
                jumpTo(Locator(i, 0))
            })
            list.addView(row)
            list.addView(ui.rule(soft = true))
            if (i == current) currentRow = row
        }
        val scroll = ScrollView(this).apply { addView(list) }
        col.addView(ui.topBar("Contents", "", "Close", onRight = { closeSheet() }))
        col.addView(scroll, ui.lp(h = (resources.displayMetrics.heightPixels * 0.62f).toInt()))
        showSheet(col)
        currentRow?.let { row -> scroll.post { scroll.scrollTo(0, (row.top - ui.dp(120)).coerceAtLeast(0)) } }
    }

    private fun goHome() {
        save()
        startActivity(Intent(this, HomeActivity::class.java).setAction(Intent.ACTION_MAIN))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheet != null) closeSheet() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_ID = "book_id"
        private val MARGINS = intArrayOf(12, 20, 30, 42)
        private val loader = Executors.newSingleThreadExecutor()
    }
}

/** Keeps the last opened reflowable book in memory so returning to it is instant. */
object BookCache {
    private var id: String? = null
    private var book: FlowBook? = null

    @Synchronized
    fun load(context: android.content.Context, entry: BookEntry): FlowBook {
        if (id == entry.id) book?.let { return it }
        val cr = context.contentResolver
        val uri = Uri.parse(entry.uri)
        val loaded = when (entry.format) {
            Format.EPUB -> Epub.load(cr.openInputStream(uri)!!.use { Epub.readTextEntries(it) }, entry.title)
            Format.TXT -> TextBook.load(cr.openInputStream(uri)!!.use { it.readBytes() }, entry.title)
            Format.PDF -> throw IllegalArgumentException("PDF is not reflowable")
        }
        loaded.chapterLengths // computed here, off the main thread
        id = entry.id
        book = loaded
        return loaded
    }
}
