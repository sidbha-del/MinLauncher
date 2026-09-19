package app.booklauncher.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.booklauncher.HomeActivity
import app.booklauncher.book.Progress
import app.booklauncher.data.BookEntry
import app.booklauncher.data.NowPlaying
import app.booklauncher.data.Prefs
import java.util.Date
import java.util.Locale

/** Home: the book you're reading, where you stopped, and one tap to continue. */
class HomeScreen(host: HomeActivity) : Screen(host) {
    override val isHome = true
    private var clock: TextView? = null
    private var date: TextView? = null
    private var battery: TextView? = null

    override fun build(): View {
        val frame = GestureFrame(host).apply {
            onSwipeUp = { host.push(AppsScreen(host)) }
            onSwipeRight = { host.push(LibraryScreen(host)) }
            onSwipeDown = { expandNotifications(host) }
            onLongPress = { HomeMenu.show(host) }
        }
        val col = ui.vertical()
        col.addView(statusBar())

        HomeNotice.build(host)?.let { col.addView(it) }
        val book = host.library.current()
        val listening = NowPlaying.get(host).current(host.prefs.nowPlayingAllAudio)
        if (book == null && listening == null) col.addView(emptyState())
        else if (book != null) nowReading(col, book, compact = listening != null)
        if (listening != null) col.addView(nowListening(listening)) else listeningPrompt()?.let { col.addView(it) }

        val pins = pinnedApps()
        if (pins != null) col.addView(pins)
        col.addView(ui.space(), ui.lp(h = 0, weight = 1f))
        col.addView(TimeStats.homeBlock(host))
        col.addView(ui.navBar(
            "← Library" to { host.push(LibraryScreen(host)) },
            "Apps ↑" to { host.push(AppsScreen(host)) },
        ))
        frame.addView(col)
        return frame
    }

    override fun onTimeTick() {
        updateStatus()
        updateListeningProgress()
    }

    private var listenBar: SegmentBar? = null
    private var listenLine: TextView? = null

    /** What an audiobook app is playing, with its own transport controls. */
    private fun nowListening(s: NowPlaying.State): View {
        val np = NowPlaying.get(host)
        val col = ui.vertical()
        col.addView(ui.mono("Now listening · ${s.appLabel}", 11f).also { ui.margins(it, 18, 24, 18, 0) })

        val row = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val art = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            colorFilter = p.imageFilter
            if (s.art != null) setImageBitmap(s.art) else setBackgroundColor(Covers.fallbackColor(s.title))
            contentDescription = s.title
        }
        row.addView(art, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        val names = ui.vertical()
        names.addView(ui.serif(s.title, 16f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (s.subtitle.isNotBlank()) names.addView(ui.text(s.subtitle, 13f, p.soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.also { ui.margins(it, t = 4) })
        row.addView(names, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) })
        ui.tappable(row, { np.open(host, s) })
        col.addView(row)
        ui.margins(row, 18, 12, 18, 0)

        if (s.durationMs > 0) {
            val bar = SegmentBar(host, p, s.fraction)
            listenBar = bar
            col.addView(bar, ui.lp().apply { setMargins(ui.dp(18), ui.dp(14), ui.dp(18), 0) })
        }
        val line = ui.mono(listeningLine(s), 11f)
        listenLine = line
        col.addView(line.also { ui.margins(it, 18, 7, 18, 0) })

        val controls = ui.horizontal()
        controls.addView(ui.boxButton("−30s") { np.jump(s, -30_000) }, ui.lp(0, weight = 1f))
        controls.addView(ui.boxButton(if (s.playing) "Pause" else "Play") { np.togglePlay(s) },
            ui.lp(0, weight = 1.4f).apply { leftMargin = ui.dp(8); rightMargin = ui.dp(8) })
        controls.addView(ui.boxButton("+30s") { np.jump(s, 30_000) }, ui.lp(0, weight = 1f))
        col.addView(controls)
        ui.margins(controls, 18, 14, 18, 0)
        return col
    }

    /**
     * Android hides other apps' playback until the user grants access, so without this line the
     * feature is invisible. Shown only when an audiobook app is installed, until allowed or dismissed.
     */
    private fun listeningPrompt(): View? {
        val np = NowPlaying.get(host)
        if (host.prefs.nowPlayingPromptDismissed || np.isAllowed()) return null
        val appName = np.installedAudiobookApp() ?: return null
        val col = ui.vertical()
        col.addView(ui.mono("Now listening", 11f).also { ui.margins(it, 18, 24, 18, 0) })
        col.addView(ui.text("Show what $appName is playing here, with play and skip?", 15f).apply {
            setLineSpacing(0f, 1.3f)
        }.also { ui.margins(it, 18, 8, 18, 0) })
        val actions = ui.horizontal()
        actions.addView(ui.boxButton("Allow") {
            if (!np.openAccessSettings(host)) Toast.makeText(host, "Open Settings → Notification access", Toast.LENGTH_LONG).show()
        }, ui.lp(0, weight = 1f).apply { rightMargin = ui.dp(8) })
        actions.addView(ui.boxButton("Not now") { host.prefs.nowPlayingPromptDismissed = true }, ui.lp(0, weight = 1f))
        col.addView(actions)
        ui.margins(actions, 18, 12, 18, 0)
        return col
    }

    private fun listeningLine(s: NowPlaying.State): String {
        val state = if (s.playing) "Playing" else "Paused"
        if (s.durationMs <= 0) return state
        val left = (s.durationMs - s.positionMs).coerceAtLeast(0) / 60_000
        val time = if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
        return "$state · $time · ${Progress.percent(s.fraction)}%"
    }

    private fun updateListeningProgress() {
        val line = listenLine ?: return
        val s = NowPlaying.get(host).current(host.prefs.nowPlayingAllAudio) ?: return
        line.text = listeningLine(s).uppercase()
        listenBar?.let { it.fraction = s.fraction; it.invalidate() }
    }

    /** Time, date, battery. Long-press opens the Home menu (Settings, ink, books). */
    private fun statusBar(): View {
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(10))
        }
        clock = ui.mono("", 13f, p.text)
        date = ui.mono("", 13f, p.text).apply { gravity = Gravity.CENTER }
        battery = ui.mono("", 13f, p.text).apply { gravity = Gravity.END }
        row.addView(clock, ui.lp(0, weight = 1f))
        row.addView(date, ui.lp(0, weight = 1.4f))
        row.addView(battery, ui.lp(0, weight = 1f))
        row.setOnLongClickListener {
            HomeMenu.show(host)
            true
        }
        row.contentDescription = "Status. Long-press for settings."
        updateStatus()
        return ui.vertical().apply {
            addView(row)
            addView(ui.rule())
        }
    }

    private fun updateStatus() {
        val now = Date()
        clock?.text = DateFormat.format(if (DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm", now)
        date?.text = DateFormat.format("EEE d MMM", now).toString().uppercase(Locale.getDefault())
        battery?.text = batteryPercent(host)?.let { "$it%" } ?: ""
    }

    /** [compact]: something is also playing, so the excerpt gives up some lines to fit both. */
    private fun nowReading(col: LinearLayout, book: BookEntry, compact: Boolean = false) {
        val opened = book.openedAt > 0
        col.addView(ui.mono(if (opened) "Now reading" else "Up next", 11f).also { ui.margins(it, 18, 22, 18, 0) })

        val excerpt = book.excerpt.ifBlank {
            if (opened) "" else "A new book. Open it to begin."
        }
        if (excerpt.isNotBlank()) {
            val ex = ui.serif((if (opened) "… " else "") + excerpt, 18f, italic = true).apply {
                maxLines = if (compact) 3 else 7
                ellipsize = TextUtils.TruncateAt.END
            }
            ui.tappable(ex, { host.openBook(book) })
            col.addView(ex)
            ui.margins(ex, 18, 10, 18, 0)
        }

        val meta = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        meta.addView(CoverView(host, p, book), LinearLayout.LayoutParams(ui.dp(44), ui.dp(64)))
        val names = ui.vertical()
        names.addView(ui.serif(book.title, 17f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        if (book.author.isNotBlank()) names.addView(ui.text(book.author, 13f, p.soft).also { ui.margins(it, t = 4) })
        meta.addView(names, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) })
        ui.tappable(meta, { host.openBook(book) }, { BookActions.show(host, book) })
        col.addView(meta)
        ui.margins(meta, 18, 20, 18, 0)

        col.addView(SegmentBar(host, p, book.progress), ui.lp().apply { setMargins(ui.dp(18), ui.dp(18), ui.dp(18), 0) })
        col.addView(ui.mono(progressLine(book), 11f).also { ui.margins(it, 18, 7, 18, 0) })

        val go = ui.boxButton(if (opened) "Continue reading" else "Start reading") { host.openBook(book) }
        col.addView(go)
        ui.margins(go, 18, 20, 18, 0)
    }

    private fun progressLine(book: BookEntry): String {
        val parts = ArrayList<String>()
        if (book.chapterTitle.isNotBlank()) parts += book.chapterTitle.take(28)
        if (book.pages > 0) parts += "p. ${book.page + 1}/${book.pages}"
        parts += "${Progress.percent(book.progress)}%"
        return parts.joinToString(" · ")
    }

    private fun emptyState(): View {
        val box = ui.vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(20))
            background = ui.box(0, dashed = true)
        }
        box.addView(ui.serif("❦", 30f).apply { gravity = Gravity.CENTER })
        box.addView(ui.serif("Nothing on your shelf yet", 17f, bold = true).apply { gravity = Gravity.CENTER }.also { ui.margins(it, t = 8) })
        box.addView(ui.text("Start with a free classic in one tap, or add your own books.", 14f, p.soft).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }.also { ui.margins(it, t = 8, b = 16) })
        box.addView(ui.boxButton("Free classics") { Starter.show(host) }, ui.lp())
        box.addView(ui.boxButton("Add my books") { AddBooks.show(host) }, ui.lp().apply { topMargin = ui.dp(10) })
        return ui.vertical().apply {
            addView(ui.mono("Now reading", 11f).also { ui.margins(it, 18, 22, 18, 0) })
            addView(box)
            ui.margins(box, 18, 24, 18, 0)
        }
    }

    /** Up to four pinned apps, only when the user pinned some. */
    private fun pinnedApps(): View? {
        val keys = host.prefs.pinned
        if (keys.isEmpty()) return null
        val byKey = host.catalog.apps.associateBy { it.key }
        val apps = keys.mapNotNull { byKey[it] }
        if (apps.isEmpty()) return null
        val row = ui.horizontal()
        val size = ui.dp(36)
        for (app in apps.take(Prefs.MAX_PINS)) {
            val cell = ui.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(10), 0, ui.dp(8))
            }
            val icon = ImageView(host)
            cell.addView(icon, LinearLayout.LayoutParams(size, size))
            host.catalog.loadIcon(app, size, p.ink, p.dark) { icon.setImageBitmap(it) }
            cell.addView(ui.text(host.prefs.label(app.key, app.label), 12f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }.also { ui.margins(it, 4, 6, 4, 0) })
            ui.tappable(cell, { host.launchApp(app) }, { AppActions.show(host, app) })
            cell.contentDescription = app.label
            row.addView(cell, ui.lp(0, weight = 1f))
        }
        repeat(Prefs.MAX_PINS - apps.size.coerceAtMost(Prefs.MAX_PINS)) { row.addView(ui.space(), ui.lp(0, weight = 1f)) }
        return ui.vertical().apply {
            addView(ui.rule(soft = true))
            addView(row)
            ui.margins(this, 18, 20, 18, 0)
        }
    }

    companion object {
        /**
         * Pulls down the notification shade, as swiping down does on most home screens. Uses the
         * status-bar service that launchers have long relied on; does nothing where it's blocked.
         */
        fun expandNotifications(context: Context) {
            runCatching {
                val service = context.getSystemService("statusbar")
                Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
            }
        }

        fun batteryPercent(context: Context): Int? {
            val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (level >= 0 && scale > 0) level * 100 / scale else null
        }
    }
}
