package app.booklauncher.ui

import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import app.booklauncher.HomeActivity
import app.booklauncher.data.Shelf

/** The library as shelves: Reading, To read, Finished. Tap a spine to read, long-press for actions. */
class LibraryScreen(host: HomeActivity) : Screen(host) {

    override fun build(): View {
        val lib = host.library
        val frame = GestureFrame(host).apply { onSwipeLeft = { host.pop() } }
        val col = ui.vertical()
        val count = lib.books.size
        val stack = host.prefs.libraryStack
        col.addView(ui.topBar("Library", "", onRight = { host.push(SettingsScreen(host)) },
            rightIcon = app.booklauncher.R.drawable.ic_settings, rightIconLabel = "Settings"))
        if (count > 0) col.addView(viewSwitch(count, stack))

        val content = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
        if (count == 0) {
            content.addView(ui.text("No books yet. Add a folder and new books there appear here automatically.", 15f, p.soft).apply {
                setLineSpacing(0f, 1.3f)
            }.also { ui.margins(it, 18, 28, 18, 16) })
            content.addView(ui.boxButton("Add books") { AddBooks.show(host) }.also { ui.margins(it, 18, 0, 18, 0) })
        }
        val current = lib.current()?.id
        for ((shelf, label) in listOf(Shelf.READING to "Reading", Shelf.TO_READ to "To read", Shelf.FINISHED to "Finished")) {
            val books = lib.onShelf(shelf)
            if (books.isEmpty()) continue
            content.addView(ui.mono("$label · ${books.size}", 11f).also { ui.margins(it, 18, 22, 18, 8) })
            content.addView(if (stack) stackOf(books, current) else shelfOf(books, current))
        }
        otherReadingApps()?.let { content.addView(it) }
        col.addView(ScrollView(host).apply { addView(content) }, ui.lp(h = 0, weight = 1f))
        col.addView(ui.navBar(
            "Home →" to { host.goHome() },
            "Apps ↑" to { host.push(AppsScreen(host)) },
        ))
        frame.addView(col)
        return frame
    }

    /** Handoff to installed ebook apps (Kindle, Play Books, …): their books live there, so we just open them. */
    private fun otherReadingApps(): View? {
        val apps = app.booklauncher.data.ReaderApps.installed(host.catalog, host.prefs.hidden)
        if (apps.isEmpty()) return null
        val col = ui.vertical()
        col.addView(ui.mono("Other reading apps · ${apps.size}", 11f).also { ui.margins(it, 18, 30, 18, 8) })
        col.addView(ui.rule())
        val size = ui.dp(30)
        for (entry in apps) {
            val row = ui.horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
            }
            val icon = android.widget.ImageView(host)
            row.addView(icon, android.widget.LinearLayout.LayoutParams(size, size))
            host.catalog.loadIcon(entry, size, p.ink, p.dark) { icon.setImageBitmap(it) }
            row.addView(ui.text(host.prefs.label(entry.key, entry.label), 16f), ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) })
            row.addView(ui.mono("Open →", 11f, p.text))
            ui.tappable(row, { host.launchApp(entry) }, { AppActions.show(host, entry) })
            row.contentDescription = "Open ${entry.label}"
            col.addView(row)
            col.addView(ui.rule(soft = true))
        }
        return col
    }

    /** Book count on the left, SHELF | STACK on the right. */
    private fun viewSwitch(count: Int, stack: Boolean): View {
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
        }
        row.addView(ui.mono(if (count == 1) "1 book" else "$count books", 11f), ui.lp(0, weight = 1f))
        row.addView(ui.mono("+ Add", 10.5f, p.text).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
            background = ui.box(0)
            setOnClickListener { AddBooks.show(host) }
        }, ui.lp(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        for ((label, on) in listOf("Shelf" to !stack, "Stack" to stack)) {
            val chip = ui.mono(label, 10.5f, if (on) p.bg else p.text).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
                background = ui.box(if (on) p.text else 0)
                if (!on) setOnClickListener { host.prefs.libraryStack = label == "Stack" }
            }
            row.addView(chip, ui.lp(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(8) })
        }
        return ui.vertical().apply {
            addView(row)
            addView(ui.rule(soft = true))
        }
    }

    private fun shelfOf(books: List<app.booklauncher.data.BookEntry>, current: String?): View {
        val layout = ShelfLayout(host, p).apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        books.forEachIndexed { i, book ->
            val spine = SpineView(host, p, book, solid = i % 2 == 0, ribbon = book.id == current)
            ui.tappable(spine, { host.openBook(book) }, { BookActions.show(host, book) })
            layout.addView(spine)
        }
        return layout
    }

    /** Books lying flat, one per row, on a board: every title reads left to right. */
    private fun stackOf(books: List<app.booklauncher.data.BookEntry>, current: String?): View {
        val col = ui.vertical().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        books.forEachIndexed { i, book ->
            val v = StackBookView(host, p, book, solid = i % 2 == 0, ribbon = book.id == current)
            ui.tappable(v, { host.openBook(book) }, { BookActions.show(host, book) })
            col.addView(v, ui.lp().apply { if (i > 0) topMargin = ui.dp(3) })
        }
        col.addView(ui.rule(thicknessDp = 3f))
        return col
    }
}

/** Long-press on a book: open, move between shelves, details, remove. */
object BookActions {
    fun show(host: HomeActivity, bookArg: app.booklauncher.data.BookEntry) {
        val ui = host.ui
        val p = ui.p
        val book = host.library.find(bookArg.id) ?: return
        val col = ui.vertical()
        val head = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14))
        }
        head.addView(CoverView(host, p, book), android.widget.LinearLayout.LayoutParams(ui.dp(34), ui.dp(50)))
        val names = ui.vertical()
        names.addView(ui.serif(book.title, 16f, bold = true).apply { maxLines = 2 })
        val sub = listOf(book.author, book.format.name, "${app.booklauncher.book.Progress.percent(book.progress)}%").filter { it.isNotBlank() }
        names.addView(ui.text(sub.joinToString(" · "), 12.5f, p.soft).also { ui.margins(it, t = 3) })
        head.addView(names, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(12) })
        col.addView(head)
        col.addView(ui.rule())
        col.addView(ui.row("Open") { host.openBook(book) })
        for ((shelf, label) in listOf(Shelf.READING to "Move to Reading", Shelf.TO_READ to "Move to To read", Shelf.FINISHED to "Mark finished")) {
            if (shelf != book.shelf) col.addView(ui.row(label) {
                host.library.update(book.id) { it.copy(shelf = shelf) }
                host.dismissSheet()
            })
        }
        col.addView(ui.row("Book details") { details(host, book) })
        if (book.olKey.isNotEmpty()) {
            col.addView(ui.row("Wrong cover or title? Reset") {
                app.booklauncher.data.OnlineMeta.reset(host, book.id)
                Covers.forget(book.id)
                host.dismissSheet()
            })
        } else if (host.prefs.onlineMeta && book.olChecked) {
            col.addView(ui.row("Look up details online") {
                app.booklauncher.data.OnlineMeta.retry(host, book.id)
                host.dismissSheet()
            })
        }
        col.addView(ui.row("Remove from library", warn = true) {
            host.library.remove(book.id)
            Covers.forget(book.id)
            host.dismissSheet()
        })
        host.showSheet(col)
    }

    private fun details(host: HomeActivity, book: app.booklauncher.data.BookEntry) {
        val ui = host.ui
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(8)) }
        col.addView(ui.mono("Book details", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        fun line(k: String, v: String) {
            if (v.isBlank()) return
            col.addView(ui.row(k, v))
        }
        line("Title", book.title)
        line("Author", book.author)
        line("Format", book.format.name)
        if (book.size > 0) line("Size", "%.1f MB".format(book.size / 1_048_576.0))
        line("Source", if (book.folder != null) "Watched folder" else "Added file")
        line("Sync ID", book.docHash?.take(12) ?: "")
        host.showSheet(col)
    }
}

/** Add books: a watched folder or individual files. E-reader sync is shown as coming later. */
object AddBooks {
    fun show(host: HomeActivity) {
        val ui = host.ui
        val col = ui.vertical()
        col.addView(ui.mono("Add books", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        col.addView(ui.row("Free classics", "One tap") { Starter.show(host) })
        col.addView(ui.row("Free catalogs", "Gutenberg & more") {
            host.dismissSheet()
            host.push(CatalogsScreen(host))
        })
        col.addView(ui.row("Choose a folder", "Keeps watching") { host.pickFolder() })
        col.addView(ui.row("Choose files", "EPUB PDF TXT") { host.pickFiles() })
        col.addView(ui.row("Sync with e-reader", later = true))
        col.addView(ui.row("KOReader progress sync", later = true))
        col.addView(ui.mono("Cancel", 12f, ui.p.text).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(16), 0, ui.dp(16))
            ui.tappable(this, { host.dismissSheet() })
        })
        host.showSheet(col)
    }
}
