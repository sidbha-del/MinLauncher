package app.readfirst.ui

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.data.HomeRole
import app.readfirst.data.Ink
import app.readfirst.data.NowPlaying
import app.readfirst.data.Prefs
import app.readfirst.data.Ringer
import app.readfirst.data.RingerNotification

/** Settings, in six short sections: Reading, Home, Listening, Focus, Library, About. */
class SettingsScreen(host: HomeActivity) : Screen(host) {

    override fun build(): View {
        val prefs = host.prefs
        val col = ui.vertical()
        col.addView(ui.topBar("Settings", "", "Done", onRight = { host.pop() }))
        val list = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(32)) }

        // ---- Reading -------------------------------------------------------------------------
        list.addView(ui.section("Reading", first = true))
        val inkChoice = ui.toggle("Color", "Black", prefs.ink == Ink.COLOR, { prefs.ink = Ink.COLOR }, { prefs.ink = Ink.BLACK })
        list.addView(ui.setting("Ink", "Black ink: no animation", trailing = inkChoice, trailingWidth = 150))
        list.addView(ui.setting("Page", "Night turns the ink light"))
        list.addView(PageLook.chooser(ui, prefs.launcherPage) { prefs.launcherPage = it }.also { ui.margins(it, 24, 0, 18, 12) })
        list.addView(ui.switchSetting("Books match page", null, prefs.booksShareLauncherPage) { prefs.booksShareLauncherPage = it })
        if (!prefs.booksShareLauncherPage) {
            list.addView(ui.setting("Book page"))
            list.addView(PageLook.chooser(ui, prefs.booksOwnPage) { prefs.booksOwnPage = it }.also { ui.margins(it, 24, 0, 18, 12) })
        }

        // ---- Home ----------------------------------------------------------------------------
        list.addView(ui.section("Home"))
        val isDefault = HomeRole.isDefault(host)
        list.addView(ui.setting("Home app", value = if (isDefault) "ReadFirst" else "Not set") { homeAppSheet(isDefault) })
        list.addView(ui.setting("Add widget", "Works on any home screen") {
            if (!app.readfirst.widget.ReadingWidget.requestPin(host)) {
                Toast.makeText(host, "Long-press your home screen → Widgets → ReadFirst", Toast.LENGTH_LONG).show()
            }
        })
        list.addView(ui.switchSetting("Dock on Library & Apps", "${prefs.pinned.size} of ${Prefs.MAX_PINS} apps in the dock", prefs.dockEverywhere) {
            prefs.dockEverywhere = it
        })
        list.addView(ui.setting("Home status", value = prefs.statusBarStyle.label) {
            val values = app.readfirst.data.StatusBarStyle.entries
            prefs.statusBarStyle = values[(values.indexOf(prefs.statusBarStyle) + 1) % values.size]
        })
        val canSilence = Ringer.hasAccess(host)
        list.addView(ui.switchSetting("Ringer slider", if (canSilence) "On an edge of Home" else "Silent needs access", prefs.ringerSlider) {
            prefs.ringerSlider = it
        })
        list.addView(ui.setting("Slider side", value = if (prefs.ringerSliderLeft) "Left" else "Right") {
            prefs.ringerSliderLeft = !prefs.ringerSliderLeft
            app.readfirst.data.FlipTimer.pushSliderPlace(host, prefs.ringerSliderLeft, prefs.ringerSliderPos)
        })
        val heights = listOf(10 to "Top", 22 to "Upper", 34 to "Default", 50 to "Middle", 65 to "Lower", 78 to "Bottom")
        list.addView(ui.setting("Slider height", value = heights.minByOrNull { kotlin.math.abs(it.first - prefs.ringerSliderPos) }!!.second) {
            val i = heights.indexOfFirst { it.first == heights.minByOrNull { h -> kotlin.math.abs(h.first - prefs.ringerSliderPos) }!!.first }
            prefs.ringerSliderPos = heights[(i + 1) % heights.size].first
            app.readfirst.data.FlipTimer.pushSliderPlace(host, prefs.ringerSliderLeft, prefs.ringerSliderPos)
        })
        list.addView(ui.switchSetting("Ringer in notifications", "Ring, vibrate, silent buttons", prefs.ringerNotification) {
            RingerNotification.setEnabled(host, it)
        })
        list.addView(ui.setting("Silent mode access", value = if (canSilence) "Allowed" else "Not allowed") {
            if (canSilence) openSilentAccess()
            else ui.infoSheet(
                host, "Silent mode",
                "Android only lets an app switch Silent on or off with Do Not Disturb access, and treats Silent as Do Not Disturb.",
                "Open settings",
            ) { openSilentAccess() }
        })

        // ---- Listening -----------------------------------------------------------------------
        list.addView(ui.section("Listening"))
        val allowed = NowPlaying.get(host).isAllowed()
        list.addView(ui.setting("Show audiobooks on Home", value = if (allowed) "On" else "Allow access") {
            if (allowed) openMediaAccessSettings()
            else ui.infoSheet(
                host, "Now listening",
                "Shows what Audible, Libby and other audiobook apps are playing. Android files this under notification access; ReadFirst only reads what's playing, never your notifications.",
                "Open settings",
            ) { openMediaAccessSettings() }
        })
        if (allowed) {
            list.addView(ui.switchSetting("Include all audio", "Not only audiobook apps", prefs.nowPlayingAllAudio) { prefs.nowPlayingAllAudio = it })
        }

        // ---- Focus ---------------------------------------------------------------------------
        list.addView(ui.section("Focus"))
        val paused = app.readfirst.data.Focus.distracting(prefs, host.catalog).size
        list.addView(ui.setting("Pause before apps", value = if (prefs.pauseEnabled) (if (paused == 1) "1 app" else "$paused apps") else "Off") {
            host.push(FocusAppsScreen(host))
        })
        val usage = app.readfirst.data.Focus.hasUsageAccess(host)
        list.addView(ui.setting("Reading vs scrolling", if (usage) "Usage access is on" else "Needs usage access", value = if (usage) "On" else "Allow") {
            TimeStats.openUsageAccess(host)
        })
        list.addView(ui.setting("Today's screen time") { TimeStats.sheet(host) })
        val checks = app.readfirst.data.PhoneCheck.run(host)
        if (checks.isNotEmpty()) {
            list.addView(ui.setting("Device check", value = "${checks.size} to check") { deviceCheckSheet() })
        }

        // ---- Library -------------------------------------------------------------------------
        list.addView(ui.section("Library"))
        list.addView(ui.setting("Book folders", value = "${host.library.folders.size}") { foldersSheet() })
        list.addView(ui.setting("Free catalogs", "Gutenberg and more") { host.push(CatalogsScreen(host)) })
        list.addView(ui.switchSetting("Find covers online", "Also fills in book details", prefs.onlineMeta) {
            prefs.onlineMeta = it
            if (it) app.readfirst.data.OnlineMeta.lookupPending(host)
        })
        list.addView(ui.setting("Hidden apps", value = "${prefs.hidden.size}") { hiddenSheet() })

        // ---- About ---------------------------------------------------------------------------
        list.addView(ui.section("About"))
        list.addView(ui.setting("Version", value = app.readfirst.BuildConfig.VERSION_NAME))
        list.addView(ui.setting("Project page", "On GitHub") { openLink("https://sidbha-del.github.io/MinLauncher") })
        list.addView(ui.setting("Buy me a coffee", "Say thanks") { openLink("https://www.buymeacoffee.com/rashidua82") })
        list.addView(ui.setting("Suggest a feature", "Opens your mail app") { suggestSheet() })

        val scroll = ScrollView(host).apply { addView(list) }
        // Changing a setting rebuilds the screen; keep the reader where they were.
        scroll.post { scroll.scrollTo(0, savedScroll) }
        scroll.viewTreeObserver.addOnScrollChangedListener { savedScroll = scroll.scrollY }
        col.addView(scroll, ui.lp(h = 0, weight = 1f))
        return col
    }

    /** The old three launcher-role rows in one place: make default (or change it), try the old one, go back. */
    private fun homeAppSheet(isDefault: Boolean) {
        val col = ui.vertical()
        col.addView(ui.mono("Home app", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        col.addView(ui.row(if (isDefault) "Change in Android settings" else "Make ReadFirst your home app", if (isDefault) "Default now" else null) {
            host.dismissSheet(refresh = false)
            if (isDefault) HomeRole.openHomeSettings(host) else HomeRole.requestDefault(host)
        })
        col.addView(ui.row("Open previous launcher once") {
            host.dismissSheet(refresh = false)
            if (!HomeRole.openOtherLauncherOnce(host)) Toast.makeText(host, "No other home app found", Toast.LENGTH_SHORT).show()
        })
        if (isDefault) col.addView(ui.row("Switch back to previous launcher") {
            host.dismissSheet(refresh = false)
            HomeRole.switchBackToStock(host)
        })
        host.showSheet(col)
    }

    private fun deviceCheckSheet() {
        val col = ui.vertical()
        col.addView(ui.mono("Device check", 11f).also { ui.margins(it, 18, 16, 18, 4) })
        col.addView(ui.text("Some brands stop apps to save battery.", 13f, p.soft).also { ui.margins(it, 18, 0, 18, 10) })
        col.addView(ui.rule())
        val checks = app.readfirst.data.PhoneCheck.run(host)
        if (checks.isEmpty()) col.addView(ui.row("Everything looks good", "✓"))
        for (c in checks) {
            col.addView(ui.row(c.title, if (c.level == app.readfirst.data.PhoneCheck.Level.PROBLEM) "Fix" else "Check") {
                if (c.id == "folders") host.pickFolder()
                else if (!c.fix(host)) Toast.makeText(host, c.hint, Toast.LENGTH_LONG).show()
            })
        }
        host.showSheet(col)
        // Coming back from Android's settings, show what is still wrong.
        host.sheetRefresh = { deviceCheckSheet() }
    }

    private fun openSilentAccess() {
        if (!Ringer.openAccessSettings(host)) Toast.makeText(host, "Open Settings → Do Not Disturb access to allow ReadFirst", Toast.LENGTH_LONG).show()
    }

    private var savedScroll = 0

    private fun openMediaAccessSettings() {
        if (!NowPlaying.get(host).openAccessSettings(host)) {
            Toast.makeText(host, "Open Settings → Notification access to allow ReadFirst", Toast.LENGTH_LONG).show()
        }
    }

    private fun openLink(url: String) {
        if (!app.readfirst.data.PhoneCheck.open(host, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(host, "No browser found on this device", Toast.LENGTH_LONG).show()
        }
    }

    /** Hands the typed suggestion to the reader's own mail app; ReadFirst never sends it itself. */
    private fun suggestSheet() {
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
        col.addView(ui.mono("Suggest a feature", 11f).also { ui.margins(it, 18, 16, 18, 10) })
        val field = EditText(host).apply {
            setTextColor(p.text)
            textSize = 16f
            hint = "What would make ReadFirst better?"
            setHintTextColor(p.soft)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            background = ui.box(0)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        col.addView(field)
        ui.margins(field, 18, 0, 18, 12)
        val row = ui.horizontal()
        row.addView(ui.boxButton("Cancel") { host.dismissSheet() }, ui.lp(0, weight = 1f).apply { rightMargin = ui.dp(6) })
        row.addView(ui.boxButton("Send") {
            val note = field.text.toString().trim()
            host.dismissSheet()
            if (note.isNotEmpty()) sendSuggestion(note)
        }, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(6) })
        col.addView(row)
        ui.margins(row, 18, 0, 18, 0)
        host.showSheet(col)
        // showSheet hides the keyboard as it attaches, so ask for it back afterwards.
        field.requestFocus()
        host.getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun sendSuggestion(note: String) {
        val body = "$note\n\n—\nReadFirst ${app.readfirst.BuildConfig.VERSION_NAME}\n${android.os.Build.MODEL}"
        val mail = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "rashidua82@gmail.com", null))
            .putExtra(Intent.EXTRA_SUBJECT, "ReadFirst — feature suggestion")
            .putExtra(Intent.EXTRA_TEXT, body)
        if (!app.readfirst.data.PhoneCheck.open(host, mail)) {
            Toast.makeText(host, "No mail app found on this device", Toast.LENGTH_LONG).show()
        }
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
        col.addView(ui.serif("A home screen that opens on your book.", 26f, bold = true).also { ui.margins(it, 18, 28, 18, 8) })
        col.addView(ui.text("Three quick steps. You can change any of them later in Settings.", 15f, p.soft).apply {
            setLineSpacing(0f, 1.3f)
        }.also { ui.margins(it, 18, 0, 18, 20) })
        col.addView(ui.rule())

        val isHome = HomeRole.isDefault(host)
        val hasBooks = host.library.books.isNotEmpty() || host.library.folders.isNotEmpty()
        col.addView(step("1", isHome, "Make this your home screen", "Android asks once. Easy to undo.") { HomeRole.requestDefault(host) })
        col.addView(ui.mono(commsReachabilityNote(), 10.5f, p.soft).apply {
            setPadding(ui.dp(56), ui.dp(2), ui.dp(18), ui.dp(12))
            setLineSpacing(0f, 1.2f)
        })
        if (!isHome) col.addView(ui.mono("Not ready to switch? Add the widget instead →", 10.5f, p.text).apply {
            setPadding(ui.dp(56), ui.dp(2), ui.dp(18), ui.dp(12))
            ui.tappable(this, {
                if (!app.readfirst.widget.ReadingWidget.requestPin(host)) {
                    Toast.makeText(host, "Long-press your home screen → Widgets → ReadFirst", Toast.LENGTH_LONG).show()
                }
            })
        })
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

    /**
     * Reassurance that switching home screens doesn't cut anyone off. Names the apps actually
     * pinned rather than assuming a dialer and a messaging app exist: a Wi-Fi tablet has neither.
     */
    private fun commsReachabilityNote(): String {
        val names = host.prefs.pinned.mapNotNull { key -> host.catalog.apps.firstOrNull { it.key == key }?.label }
        return if (names.isEmpty()) "Every app is one swipe up from Home."
        else "${names.joinToString(" and ")} — pinned to Home. Every other app is one swipe up."
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
