package app.readfirst.ui

import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.book.Opds
import app.readfirst.data.Catalogs
import app.readfirst.data.Ink

/** Long-press on Home: the way to Settings without going through the app list. */
object HomeMenu {
    fun show(host: HomeActivity) {
        val ui = host.ui
        val prefs = host.prefs
        val col = ui.vertical()
        col.addView(ui.mono("Home", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        col.addView(ui.row("Settings") {
            host.dismissSheet()
            host.push(SettingsScreen(host))
        })
        col.addView(ui.row(if (prefs.ink == Ink.BLACK) "Switch to Color ink" else "Switch to Black ink") {
            host.dismissSheet()
            prefs.toggleInk()
        })
        col.addView(ui.row("Add books") { AddBooks.show(host) })
        col.addView(ui.row("Free classics", "One tap") { Starter.show(host) })
        host.showSheet(col)
    }
}

/**
 * A handful of well-loved public-domain books from Project Gutenberg, downloaded in one tap, so
 * a new user reads within seconds instead of finding the catalog first.
 */
object Starter {
    /** [edition]: Gutenberg file suffix; the illustrated Pride and Prejudice is 25 MB, the text one 0.6 MB. */
    private data class Classic(val title: String, val author: String, val gutenbergId: Int, val note: String, val edition: String = "epub3.images")

    private val CLASSICS = listOf(
        Classic("Meditations", "Marcus Aurelius", 2680, "Short daily wisdom"),
        Classic("The Adventures of Sherlock Holmes", "Arthur Conan Doyle", 1661, "Twelve short mysteries"),
        Classic("Pride and Prejudice", "Jane Austen", 1342, "The classic romance", edition = "epub.noimages"),
        Classic("Gitanjali", "Rabindranath Tagore", 7164, "Poems, a page at a time"),
    )

    fun show(host: HomeActivity) {
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(8)) }
        col.addView(ui.mono("Free classics · Project Gutenberg", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        val gutenberg = Catalogs.all(host.prefs).first { it.id == "gutenberg" }
        for (c in CLASSICS) {
            val link = Opds.Link("https://www.gutenberg.org/ebooks/${c.gutenbergId}.${c.edition}", Opds.ACQUISITION, "application/epub+zip", "EPUB", 0)
            val entry = Opds.Entry("urn:gutenberg:${c.gutenbergId}", c.title, listOf(c.author), "", listOf(link))
            val owned = Catalogs.inLibrary(host.library, listOf(link))
            val row = ui.horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(13), ui.dp(18), ui.dp(13))
            }
            val names = ui.vertical()
            names.addView(ui.serif(c.title, 16f, bold = true).apply { maxLines = 2 })
            names.addView(ui.text("${c.author} · ${c.note}", 13f, p.soft).also { ui.margins(it, t = 3) })
            row.addView(names, ui.lp(0, weight = 1f))
            val action: TextView = ui.mono(if (owned != null) "Open" else "Get", 11f, p.text)
            row.addView(action, ui.lp(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(12) })
            var busy = false
            var attempt = 0
            ui.tappable(row, {
                val have = Catalogs.inLibrary(host.library, listOf(link))
                when {
                    have != null -> host.openBook(have)
                    busy -> {}
                    else -> {
                        busy = true
                        action.text = "…"
                        val thisAttempt = ++attempt
                        var progressed = false
                        // No answer at all after a while usually means no connection: offer Retry.
                        row.postDelayed({
                            if (busy && !progressed && attempt == thisAttempt) {
                                busy = false
                                action.text = "Retry"
                                Toast.makeText(host, "Can't reach Project Gutenberg. Check your connection.", Toast.LENGTH_LONG).show()
                            }
                        }, 40_000)
                        Catalogs.download(host, gutenberg, entry, link,
                            onProgress = { f -> progressed = true; action.text = "${(f * 100).toInt()}%" },
                            onDone = { book, err ->
                                busy = false
                                if (book != null) {
                                    host.dismissSheet()
                                    Toast.makeText(host, "${c.title} is ready", Toast.LENGTH_SHORT).show()
                                } else {
                                    action.text = "Retry"
                                    Toast.makeText(host, err ?: "Download failed", Toast.LENGTH_LONG).show()
                                }
                            })
                    }
                }
            })
            col.addView(row)
            col.addView(ui.rule(soft = true))
        }
        col.addView(ui.text("More in Library → + Add → Free catalogs.", 12.5f, p.soft).also { ui.margins(it, 18, 12, 18, 8) })
        host.showSheet(col)
    }
}
