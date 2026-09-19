package app.readfirst.ui

import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.data.HomeRole
import app.readfirst.data.Ink
import app.readfirst.data.NowPlaying

/** Settings: ink, home-screen role, pinned and hidden apps, book folders. */
class SettingsScreen(host: HomeActivity) : Screen(host) {

    override fun build(): View {
        val prefs = host.prefs
        val col = ui.vertical()
        col.addView(ui.topBar("Settings", "", "Done", onRight = { host.pop() }))
        val list = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(24)) }

        list.addView(ui.group("Ink"))
        list.addView(ui.toggle("Color ink", "Black ink", prefs.ink == Ink.COLOR, { prefs.ink = Ink.COLOR }, { prefs.ink = Ink.BLACK }).also {
            ui.margins(it, 18, 14, 18, 4)
        })
        list.addView(ui.text("Black ink is pure black on white with no animation. Long-press the top bar on Home to switch quickly.", 13f, p.soft).apply {
            setLineSpacing(0f, 1.3f)
        }.also { ui.margins(it, 18, 8, 18, 6) })

        list.addView(ui.group("Page"))
        list.addView(ui.mono("Launcher page", 10f).also { ui.margins(it, 18, 14, 18, 10) })
        list.addView(PageLook.chooser(ui, prefs.launcherPage) { prefs.launcherPage = it }.also { ui.margins(it, 12, 0, 18, 6) })
        list.addView(ui.mono("Books", 10f).also { ui.margins(it, 18, 20, 18, 10) })
        list.addView(ui.toggle("Same as launcher", "Own page", prefs.booksShareLauncherPage,
            { prefs.booksShareLauncherPage = true }, { prefs.booksShareLauncherPage = false }).also { ui.margins(it, 18, 0, 18, 6) })
        if (!prefs.booksShareLauncherPage) {
            list.addView(ui.mono("Book page", 10f).also { ui.margins(it, 18, 14, 18, 10) })
            list.addView(PageLook.chooser(ui, prefs.booksOwnPage) { prefs.booksOwnPage = it }.also { ui.margins(it, 12, 0, 18, 6) })
        }
        list.addView(ui.text("Night is a dark page: the ink turns light. Textures are faint and never affect text sharpness.", 13f, p.soft).apply {
            setLineSpacing(0f, 1.3f)
        }.also { ui.margins(it, 18, 10, 18, 6) })

        list.addView(ui.group("Now listening"))
        val np = NowPlaying.get(host)
        val allowed = np.isAllowed()
        list.addView(ui.row("Show audiobooks on Home", if (allowed) "On" else "Allow access") { openMediaAccessSettings() })
        if (allowed) {
            list.addView(ui.toggle("Audiobook apps", "All audio", !prefs.nowPlayingAllAudio,
                { prefs.nowPlayingAllAudio = false }, { prefs.nowPlayingAllAudio = true }).also { ui.margins(it, 18, 14, 18, 4) })
        }
        list.addView(ui.text(
            "Shows what Audible, Libby and other audiobook apps are playing, with play and skip. " +
                "Android files this under notification access; ReadFirst only reads what's playing and never your notifications.",
            13f, p.soft,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 10, 18, 6) })

        list.addView(ui.group("Focus"))
        val paused = app.readfirst.data.Focus.distracting(prefs, host.catalog).size
        list.addView(ui.row("Pause before distracting apps", if (prefs.pauseEnabled) "$paused apps" else "Off") {
            host.push(FocusAppsScreen(host))
        })
        val usage = app.readfirst.data.Focus.hasUsageAccess(host)
        list.addView(ui.row("Compare reading with scrolling", if (usage) "On" else "Allow access") { TimeStats.openUsageAccess(host) })
        list.addView(ui.row("Today's phone time") { TimeStats.sheet(host) })

        list.addView(ui.group("Phone check"))
        val checks = app.readfirst.data.PhoneCheck.run(host)
        if (checks.isEmpty()) {
            list.addView(ui.row("Everything looks good", "✓"))
        }
        for (c in checks) {
            list.addView(ui.row(c.title, if (c.level == app.readfirst.data.PhoneCheck.Level.PROBLEM) "Fix" else "Check") {
                if (c.id == "folders") host.pickFolder()
                else if (!c.fix(host)) Toast.makeText(host, c.hint, Toast.LENGTH_LONG).show()
            })
        }
        if (checks.isNotEmpty()) list.addView(ui.text(
            "Phones from some brands stop apps to save battery. These settings keep Home instant and Now listening on.",
            12.5f, p.soft,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 8, 18, 4) })

        list.addView(ui.group("Home screen"))
        val isDefault = HomeRole.isDefault(host)
        list.addView(ui.row("Default home screen", if (isDefault) "On" else "Set now") {
            if (isDefault) HomeRole.openHomeSettings(host) else HomeRole.requestDefault(host)
        })
        list.addView(ui.row("Pinned apps", "${prefs.pinned.size} / 4") { pinnedSheet() })
        list.addView(ui.row("Open previous launcher once") {
            if (!HomeRole.openOtherLauncherOnce(host)) Toast.makeText(host, "No other home app found", Toast.LENGTH_SHORT).show()
        })
        if (isDefault) list.addView(ui.row("Switch back to previous launcher") { HomeRole.switchBackToStock(host) })

        list.addView(ui.group("Library & apps"))
        list.addView(ui.row("Book folders", "${host.library.folders.size}") { foldersSheet() })
        list.addView(ui.row("Free catalogs", "Gutenberg & more") { host.push(CatalogsScreen(host)) })
        list.addView(ui.row("Find covers & details online", if (prefs.onlineMeta) "On" else "Off") {
            prefs.onlineMeta = !prefs.onlineMeta
            if (prefs.onlineMeta) app.readfirst.data.OnlineMeta.lookupPending(host)
        })
        list.addView(ui.row("Hidden apps", "${prefs.hidden.size}") { hiddenSheet() })
        list.addView(ui.row("E-reader sync", later = true))

        list.addView(ui.group("About"))
        list.addView(ui.row("Version", app.readfirst.BuildConfig.VERSION_NAME))

        val scroll = ScrollView(host).apply { addView(list) }
        // Changing a setting rebuilds the screen; keep the reader where they were.
        scroll.post { scroll.scrollTo(0, savedScroll) }
        scroll.viewTreeObserver.addOnScrollChangedListener { savedScroll = scroll.scrollY }
        col.addView(scroll, ui.lp(h = 0, weight = 1f))
        return col
    }

    private var savedScroll = 0

    private fun openMediaAccessSettings() {
        if (!NowPlaying.get(host).openAccessSettings(host)) {
            Toast.makeText(host, "Open Settings → Notification access to allow ReadFirst", Toast.LENGTH_LONG).show()
        }
    }

    private fun pinnedSheet() {
        val col = ui.vertical()
        col.addView(ui.mono("Pinned apps", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        val byKey = host.catalog.apps.associateBy { it.key }
        val pinned = host.prefs.pinned
        if (pinned.isEmpty()) {
            col.addView(ui.text("Long-press any app in Apps and choose Pin to home. Up to four.", 14f, p.soft).apply {
                setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(20))
            })
        }
        for (key in pinned) {
            val label = byKey[key]?.let { host.prefs.label(key, it.label) } ?: "Uninstalled app"
            col.addView(ui.row(label, "Unpin") { host.prefs.pinned = host.prefs.pinned - key; pinnedSheet() })
        }
        host.showSheet(col)
    }

    private fun hiddenSheet() {
        val col = ui.vertical()
        col.addView(ui.mono("Hidden apps", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        val byKey = host.catalog.apps.associateBy { it.key }
        val hidden = host.prefs.hidden.toList()
        if (hidden.isEmpty()) {
            col.addView(ui.text("Nothing hidden. Long-press an app in Apps to hide it.", 14f, p.soft).apply {
                setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(20))
            })
        }
        for (key in hidden) {
            val label = byKey[key]?.label ?: "Uninstalled app"
            col.addView(ui.row(label, "Show") { host.prefs.hidden = host.prefs.hidden - key; hiddenSheet() })
        }
        host.showSheet(col)
    }

    private fun foldersSheet() {
        val col = ui.vertical()
        col.addView(ui.mono("Book folders", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        for (tree in host.library.folders) {
            val name = Uri.decode(Uri.parse(tree).lastPathSegment.orEmpty()).substringAfter(':').ifBlank { "Folder" }
            col.addView(ui.row(name, "Remove", warn = false) {
                host.library.removeFolder(tree)
                foldersSheet()
            })
        }
        col.addView(ui.mono("+ Add folder", 12f, p.text).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(16), 0, ui.dp(16))
            ui.tappable(this, { host.pickFolder() })
        })
        host.showSheet(col)
    }
}

/** First run: become the home screen, add books, choose ink. */
class SetupScreen(host: HomeActivity) : Screen(host) {

    override fun build(): View {
        val prefs = host.prefs
        val col = ui.vertical()
        col.addView(ui.topBar("ReadFirst", "", "Setup"))
        col.addView(ui.serif("A phone that opens on your book.", 26f, bold = true).also { ui.margins(it, 18, 28, 18, 8) })
        col.addView(ui.text("Three quick steps. You can change any of them later in Settings.", 15f, p.soft).apply {
            setLineSpacing(0f, 1.3f)
        }.also { ui.margins(it, 18, 0, 18, 20) })
        col.addView(ui.rule())

        val isHome = HomeRole.isDefault(host)
        val hasBooks = host.library.books.isNotEmpty() || host.library.folders.isNotEmpty()
        col.addView(step("1", isHome, "Make this your home screen", "Android asks once. Easy to undo.") { HomeRole.requestDefault(host) })
        col.addView(step("2", hasBooks, "Add your books", "A free classic in one tap, or your own EPUB, PDF, TXT.") { AddBooks.show(host) })
        val ink = step("3", false, "Choose your ink", "Color ink or Black ink.", null)
        col.addView(ink)
        col.addView(ui.toggle("Color ink", "Black ink", prefs.ink == Ink.COLOR, { prefs.ink = Ink.COLOR }, { prefs.ink = Ink.BLACK }).also {
            ui.margins(it, 56, 12, 18, 14)
        })
        col.addView(ui.space(), ui.lp(h = 0, weight = 1f))
        col.addView(ui.boxButton("Continue") {
            prefs.setupDone = true
            host.goHome()
        }.also { ui.margins(it, 18, 0, 18, 22) })
        return ScrollView(host).apply {
            isFillViewport = true
            addView(col)
        }
    }

    private fun step(n: String, done: Boolean, title: String, sub: String, onClick: (() -> Unit)?): View {
        val row = ui.horizontal().apply { setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16)) }
        val badge = ui.mono(if (done) "✓" else n, 11f, if (done) p.bg else p.text).apply {
            gravity = Gravity.CENTER
            background = ui.box(if (done) p.text else 0)
        }
        row.addView(badge, android.widget.LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        val text = ui.vertical()
        text.addView(ui.text(title, 16f, face = Fonts.sansBold))
        text.addView(ui.text(sub, 13f, p.soft).also { ui.margins(it, t = 3) })
        row.addView(text, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) })
        if (onClick != null) ui.tappable(row, onClick)
        return ui.vertical().apply {
            addView(row)
            addView(ui.rule(soft = true))
        }
    }
}
