package app.readfirst.ui

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.book.Opds
import app.readfirst.data.Catalog
import app.readfirst.data.Catalogs
import app.readfirst.data.Library
import app.readfirst.data.Net
import app.readfirst.data.PhoneCheck

/** The list of free catalogs (OPDS): built-ins plus the user's own servers. */
class CatalogsScreen(host: HomeActivity) : Screen(host) {
    override fun build(): View {
        val col = ui.vertical()
        col.addView(ui.topBar("Catalogs", "", "+ Add", onRight = { editCatalog(host, null) }))
        val list = ui.vertical()
        for (c in Catalogs.all(host.prefs)) {
            val row = ui.horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
            }
            val names = ui.vertical()
            names.addView(ui.serif(c.name, 17f, bold = true))
            val note = if (c.user.isNotBlank()) "Signed in as ${c.user}" else c.note
            names.addView(ui.text(note, 13f, p.soft).also { ui.margins(it, t = 3) })
            row.addView(names, ui.lp(0, weight = 1f))
            row.addView(ui.mono("→", 14f, p.text))
            ui.tappable(row, { host.push(FeedScreen(host, c, c.url, c.name)) }, { if (!c.builtIn) editCatalog(host, c) })
            list.addView(row)
            list.addView(ui.rule(soft = true))
        }
        list.addView(ui.text(
            "Free and public-domain books. Add your own Calibre, Kavita or other OPDS server with + Add. Long-press one of your own catalogues to edit it.",
            13f, p.soft,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 16, 18, 16) })
        list.addView(moreLibraries())
        col.addView(ScrollView(host).apply { addView(list) }, ui.lp(h = 0, weight = 1f))
        col.addView(ui.navBar("← Back" to { host.pop() }, "Home" to { host.goHome() }))
        return col
    }

    /** Libraries and shops that live on the web rather than as a feed we can browse in here. */
    private fun moreLibraries(): View {
        val col = ui.vertical()
        col.addView(ui.group("More libraries"))
        for (s in Catalogs.SOURCES) col.addView(ui.row(s.name, if (s.free) "Free" else "Paid") { openWeb(host, s.url) })
        col.addView(ui.text(
            "These open in your browser. Standard Ebooks keeps its catalogue for Patrons Circle members — if you are one, add it with + Add.",
            13f, p.soft,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 10, 18, 16) })
        return col
    }

    companion object {
        /** Opens [url] in a browser. A minimal phone may not have one, so say so rather than fail silently. */
        fun openWeb(host: HomeActivity, url: String) {
            val opened = PhoneCheck.open(host, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            if (!opened) Toast.makeText(host, "Nothing here can open web pages", Toast.LENGTH_LONG).show()
        }

        /** Add (or edit) a user catalog. */
        fun editCatalog(host: HomeActivity, existing: Catalog?) {
            val ui = host.ui
            val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
            col.addView(ui.mono(if (existing == null) "Add a catalog" else "Edit catalog", 11f).also { ui.margins(it, 18, 16, 18, 10) })
            val name = field(ui, "Name, e.g. My Calibre", existing?.name.orEmpty(), InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            val url = field(ui, "OPDS address, e.g. https://…/opds", existing?.url.orEmpty(), InputType.TYPE_TEXT_VARIATION_URI)
            val user = field(ui, "Username (optional)", existing?.user.orEmpty(), InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            val pass = field(ui, "Password (optional)", existing?.pass.orEmpty(), InputType.TYPE_TEXT_VARIATION_PASSWORD)
            for (f in listOf(name, url, user, pass)) { col.addView(f); ui.margins(f, 18, 0, 18, 8) }
            val row = ui.horizontal()
            if (existing != null) row.addView(ui.boxButton("Remove") {
                Catalogs.remove(host.prefs, existing.id)
                host.dismissSheet()
            }, ui.lp(0, weight = 1f).apply { rightMargin = ui.dp(8) })
            row.addView(ui.boxButton("Save") {
                val address = url.text.toString().trim()
                if (!address.startsWith("http")) {
                    Toast.makeText(host, "Enter an address starting with https://", Toast.LENGTH_SHORT).show()
                    return@boxButton
                }
                Catalogs.save(host.prefs, Catalog(
                    id = existing?.id ?: Library.idFor(address),
                    name = name.text.toString().trim().ifEmpty { android.net.Uri.parse(address).host ?: "Catalog" },
                    url = address, note = android.net.Uri.parse(address).host.orEmpty(),
                    user = user.text.toString().trim(), pass = pass.text.toString(),
                ))
                host.dismissSheet()
            }, ui.lp(0, weight = 1f))
            col.addView(row); ui.margins(row, 18, 4, 18, 0)
            host.showSheet(col)
        }

        private fun field(ui: Ui, hint: String, value: String, variation: Int) = EditText(ui.context).apply {
            this.hint = hint
            setText(value)
            setHintTextColor(ui.p.soft)
            setTextColor(ui.p.text)
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or variation
            background = ui.box(0)
            setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11))
        }
    }
}

/**
 * One catalog page: folders, book lists (paged with More), search, and single-book pages with a
 * Download button. Loaded state lives on the screen object, so rebuilds never refetch.
 */
class FeedScreen(host: HomeActivity, private val catalog: Catalog, private val url: String, private val heading: String) : Screen(host) {
    private var feed: Opds.Feed? = null
    private var entries: List<Opds.Entry> = emptyList()
    private var next: String? = null
    private var loading = false
    private var loadingMore = false
    private var error: String? = null
    private var needsSignIn = false
    private val progress = HashMap<String, Float>()

    override fun build(): View {
        if (feed == null && !loading && error == null) load()
        val col = ui.vertical()
        col.addView(ui.topBar(heading.take(40), "", ""))
        val f = feed
        val body = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(12)) }
        when {
            error != null -> body.addView(errorView())
            f == null -> body.addView(waiting("Loading…"))
            f.singleBook != null -> body.addView(bookPage(f.singleBook!!))
            else -> {
                if (f.search != null) body.addView(searchBox(f))
                categoriesRow()?.let { body.addView(it); body.addView(ui.rule(soft = true)) }
                if (entries.isEmpty()) body.addView(ui.text("Nothing here.", 15f, p.soft).also { ui.margins(it, 18, 20, 18, 0) })
                for (e in entries) {
                    body.addView(if (e.downloads.isNotEmpty()) bookRow(e) else navRow(e))
                    body.addView(ui.rule(soft = true))
                }
                if (next != null) body.addView(ui.mono(if (loadingMore) "Loading…" else "More ↓", 12f, p.text).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(18), 0, ui.dp(18))
                    if (!loadingMore) ui.tappable(this, { loadMore() })
                })
            }
        }
        col.addView(ScrollView(host).apply { addView(body) }, ui.lp(h = 0, weight = 1f))
        col.addView(ui.navBar("← Back" to { host.pop() }, "Home" to { host.goHome() }))
        return col
    }

    // ---- loading -----------------------------------------------------------------------------

    private fun load() {
        loading = true
        fetch(url) { result ->
            loading = false
            result.onSuccess { feed = it; entries = it.entries; next = it.next }
                .onFailure { fail(it) }
        }
    }

    private fun loadMore() {
        val more = next ?: return
        loadingMore = true
        redraw()
        fetch(more) { result ->
            loadingMore = false
            result.onSuccess { entries = entries + it.entries; next = it.next }
                // Keep what's already loaded rather than throwing the page away for a failed next page.
                .onFailure { t ->
                    val why = if (t is Net.HttpError && t.code == 403) "${catalog.name} is refusing more requests for now"
                    else "Couldn't load more"
                    Toast.makeText(host, why, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun fetch(address: String, done: (Result<Opds.Feed>) -> Unit) {
        val lib = host.library
        Catalogs.net.execute {
            val r = runCatching { Opds.parse(Net.get(address, catalog.auth), address) }
            lib.postToMain {
                done(r)
                redraw()
            }
        }
    }

    private fun fail(t: Throwable) {
        needsSignIn = t is Net.AuthRequired
        error = when {
            t is Net.AuthRequired -> "${catalog.name} needs you to sign in."
            // 403 is the site refusing us, usually a rate limit that lifts on its own. Trying again
            // from in here cannot help, so say whose fault it is and offer the way out.
            t is Net.HttpError && t.code == 403 ->
                "${catalog.name} is refusing requests at the moment. That's the site rather than your " +
                    "connection or this app, and it usually clears by itself. You can browse their website instead."
            t is Net.HttpError -> "${catalog.name} answered with an error (${t.code})."
            t is IllegalArgumentException -> "That address isn't an OPDS catalogue."
            else -> "Couldn't reach ${catalog.name}. Check your connection."
        }
    }

    private fun redraw() {
        if (host.isTop(this)) host.rebuild()
    }

    private fun errorView(): View = ui.vertical().apply {
        addView(ui.text(error.orEmpty(), 15f).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 24, 18, 14) })
        val retry = ui.boxButton(if (needsSignIn) "Add a sign-in" else "Try again") {
            if (needsSignIn) CatalogsScreen.editCatalog(host, catalog) else { error = null; redraw() }
        }
        addView(retry); ui.margins(retry, 18, 0, 18, 0)
        val web = ui.boxButton("Open ${catalog.name} online") {
            CatalogsScreen.openWeb(host, Catalogs.webPage(catalog, url))
        }
        addView(web); ui.margins(web, 18, 10, 18, 0)
    }

    // ---- rows ----------------------------------------------------------------------------------

    /** A turning page over the wait, so a slow catalogue looks busy rather than stuck. */
    private fun waiting(label: String): View = ui.vertical().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(PageTurnView(host, p), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(ui.mono(label, 11f).apply { gravity = Gravity.CENTER }.also { ui.margins(it, 0, 14, 0, 0) })
        ui.margins(this, 18, 40, 18, 0)
    }

    /**
     * Gutenberg's feed offers only Popular, Latest and Random — its subjects and bookshelves live
     * on the website. Offer them from the catalogue's front page, where they're looked for.
     */
    private fun categoriesRow(): View? {
        if (url != catalog.url || catalog.web.isBlank()) return null
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        }
        val names = ui.vertical()
        names.addView(ui.serif("Browse by category", 16f, bold = true))
        names.addView(ui.text("Subjects and bookshelves, on their website", 13f, p.soft).also { ui.margins(it, t = 3) })
        row.addView(names, ui.lp(0, weight = 1f))
        row.addView(ui.mono("↗", 14f, p.text))
        ui.tappable(row, { CatalogsScreen.openWeb(host, catalog.web) })
        return row
    }

    private fun searchBox(f: Opds.Feed): View {
        val box = EditText(host).apply {
            hint = "SEARCH ${catalog.name.uppercase()}"
            setHintTextColor(p.soft)
            setTextColor(p.text)
            typeface = Fonts.mono
            textSize = 13f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ui.box(0)
            setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11))
        }
        box.setOnEditorActionListener { v, action, event ->
            val go = action == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            val q = v.text.toString().trim()
            if (go && q.isNotEmpty()) search(f, q)
            go
        }
        return box.also { ui.margins(it, 18, 14, 18, 8) }
    }

    private fun search(f: Opds.Feed, q: String) {
        val target = f.search ?: return
        if (!f.searchIsDescription) {
            host.push(FeedScreen(host, catalog, Opds.searchUrl(target, q), "Search: $q"))
            return
        }
        Catalogs.net.execute {
            val r = runCatching { Opds.parseOpenSearch(Net.get(target, catalog.auth), target) }
            host.library.postToMain {
                val template = r.getOrNull()
                val blocked = (r.exceptionOrNull() as? Net.HttpError)?.code == 403
                when {
                    template != null -> host.push(FeedScreen(host, catalog, Opds.searchUrl(template, q), "Search: $q"))
                    blocked -> Toast.makeText(host, "${catalog.name} is refusing requests right now", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(host, "Search isn't available here", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun thumb(url: String?, w: Int, h: Int): ImageView = ImageView(host).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(p.ruleSoft)
        layoutParams = LinearLayout.LayoutParams(ui.dp(w), ui.dp(h))
        NetImages.into(this, url, p, ui.dp(h * 2))
    }

    private fun navRow(e: Opds.Entry): View {
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
        }
        if (e.thumbnail != null) row.addView(thumb(e.thumbnail, 30, 30).also { (it.layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(14) })
        val names = ui.vertical()
        names.addView(ui.serif(e.title, 16f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val sub = e.author.ifBlank { e.summary }
        if (sub.isNotBlank()) names.addView(ui.text(sub, 13f, p.soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.also { ui.margins(it, t = 3) })
        row.addView(names, ui.lp(0, weight = 1f))
        row.addView(ui.mono("→", 14f, p.text), ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(10) })
        val nav = e.navigation
        ui.tappable(row, { if (nav != null) host.push(FeedScreen(host, catalog, nav.href, e.title)) })
        return row
    }

    private fun bookRow(e: Opds.Entry): View {
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
        }
        row.addView(thumb(e.thumbnail, 40, 60).also { (it.layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(14) })
        val names = ui.vertical()
        names.addView(ui.serif(e.title, 16f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (e.author.isNotBlank()) names.addView(ui.text(e.author, 13f, p.soft).also { ui.margins(it, t = 3) })
        row.addView(names, ui.lp(0, weight = 1f))
        val owned = Catalogs.inLibrary(host.library, e.downloads)
        row.addView(ui.mono(if (owned != null) "In library" else "Get", 11f, p.text), ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.dp(10) })
        ui.tappable(row, { host.push(BookPageScreen(host, catalog, e)) })
        return row
    }

    private fun bookPage(e: Opds.Entry): View = BookPageScreen.page(host, catalog, e, progress) { redraw() }
}

/** A catalog book shown on its own (from a list of books). */
class BookPageScreen(host: HomeActivity, private val catalog: Catalog, private val entry: Opds.Entry) : Screen(host) {
    private val progress = HashMap<String, Float>()

    override fun build(): View {
        val col = ui.vertical()
        col.addView(ui.topBar(catalog.name, "", ""))
        col.addView(ScrollView(host).apply {
            addView(page(host, catalog, entry, progress) { if (host.isTop(this@BookPageScreen)) host.rebuild() })
        }, ui.lp(h = 0, weight = 1f))
        col.addView(ui.navBar("← Back" to { host.pop() }, "Home" to { host.goHome() }))
        return col
    }

    companion object {
        /** Cover, title, author, summary and the Download / Open button. */
        fun page(host: HomeActivity, catalog: Catalog, e: Opds.Entry, progress: HashMap<String, Float>, redraw: () -> Unit): View {
            val ui = host.ui
            val p = ui.p
            val col = ui.vertical().apply { setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(20)) }
            val head = ui.horizontal()
            val cover = ImageView(host).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(p.ruleSoft)
            }
            NetImages.into(cover, e.cover, p, ui.dp(260))
            head.addView(cover, LinearLayout.LayoutParams(ui.dp(96), ui.dp(144)))
            val names = ui.vertical()
            names.addView(ui.serif(e.title, 20f, bold = true))
            if (e.author.isNotBlank()) names.addView(ui.text(e.author, 14f, p.soft).also { ui.margins(it, t = 6) })
            val best = e.downloads.first()
            val fmt = Opds.formatOf(best)?.name ?: ""
            val size = if (best.length > 0) " · %.1f MB".format(best.length / 1_048_576.0) else ""
            names.addView(ui.mono("$fmt$size", 10f).also { ui.margins(it, t = 10) })
            head.addView(names, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(16) })
            col.addView(head)

            val owned = Catalogs.inLibrary(host.library, e.downloads)
            val key = best.href
            val button: TextView = when {
                owned != null -> ui.boxButton("Open") { host.openBook(owned) }
                progress.containsKey(key) -> ui.boxButton("Downloading ${(progress[key]!! * 100).toInt()}%") {}
                else -> ui.boxButton("Download $fmt") {
                    progress[key] = 0f
                    redraw()
                    Catalogs.download(host, catalog, e, best,
                        onProgress = { f -> progress[key] = f; redraw() },
                        onDone = { book, err ->
                            progress.remove(key)
                            if (book != null) Toast.makeText(host, "Added to your library", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(host, err ?: "Download failed", Toast.LENGTH_LONG).show()
                            redraw()
                        })
                }
            }
            col.addView(button)
            ui.margins(button, 0, 20, 0, 0)
            if (e.summary.isNotBlank()) col.addView(ui.serif(e.summary, 15f).also { ui.margins(it, 0, 20, 0, 0) })
            return col
        }
    }
}
