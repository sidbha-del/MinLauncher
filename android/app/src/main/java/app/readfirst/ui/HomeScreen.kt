package app.readfirst.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.book.Progress
import app.readfirst.data.BookEntry
import app.readfirst.data.NowPlaying
import app.readfirst.data.PhoneCheck
import app.readfirst.data.Ringer
import app.readfirst.data.StatusBarStyle
import app.readfirst.data.Weather
import java.util.Date
import java.util.Locale

/** Home: the book you're reading, where you stopped, and one tap to continue. */
class HomeScreen(host: HomeActivity) : Screen(host) {
    override val isHome = true
    private var clock: TextView? = null
    private var date: TextView? = null
    private var battery: TextView? = null
    private var ringer: TextView? = null
    private var weatherLabel: TextView? = null
    private var alarmLabel: TextView? = null

    override fun build(): View {
        val frame = GestureFrame(host).apply {
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

        // The dock (pinned apps) is rendered once by Pager, below all three pages — not here.
        // No weighted filler in here: it would swallow the overflow, leaving the column exactly one
        // screen tall, clipped, and reporting that it has nothing to scroll to.
        val scroll = ScrollView(host).apply { addView(col) }
        frame.contentScrolls = { direction -> scroll.canScrollVertically(direction) }
        frame.addView(scroll)

        // Home's own vertical drags (the notification shade) still need the gesture frame; Library
        // and Apps are reached by paging, owned by the Pager this screen sits inside of.
        val root = ui.vertical()
        root.addView(frame, ui.lp(h = 0, weight = 1f))
        root.addView(TimeStats.homeBlock(host))
        return root
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
        val header = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ui.mono("Now listening · ${s.appLabel}", 11f), ui.lp(0, weight = 1f))
        header.addView(ui.mono("MORE", 11f).also { ui.tappable(it, { NowListeningSheet.show(host, s) }) })
        col.addView(header)
        ui.margins(header, 18, 24, 18, 0)

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
            if (s.canSeek) bar.onSeek = { fraction -> np.seekTo(s, fraction) }
            listenBar = bar
            col.addView(bar, ui.lp().apply { setMargins(ui.dp(18), ui.dp(14), ui.dp(18), 0) })
        }
        val line = ui.mono(listeningLine(s), 11f)
        listenLine = line
        col.addView(line.also { ui.margins(it, 18, 7, 18, 0) })

        val skip = host.prefs.audioSkipMs
        val controls = ui.horizontal()
        controls.addView(ui.boxButton("−${skip / 1000}s") { np.jump(s, -skip.toLong()) }, ui.lp(0, weight = 1f))
        controls.addView(ui.boxButton(if (s.playing) "Pause" else "Play") { np.togglePlay(s) },
            ui.lp(0, weight = 1.4f).apply { leftMargin = ui.dp(8); rightMargin = ui.dp(8) })
        controls.addView(ui.boxButton("+${skip / 1000}s") { np.jump(s, skip.toLong()) }, ui.lp(0, weight = 1f))
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

    /**
     * A big clock, plus date/battery/next alarm/weather depending on [Prefs.statusBarStyle] —
     * every element opens what it's about (alarms, calendar, battery, the weather page), and
     * long-press anywhere still opens the Home menu, since none of the children claim long-press
     * themselves.
     */
    private fun statusBar(): View {
        val style = host.prefs.statusBarStyle
        val col = ui.vertical().apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(12)) }

        val timeRow = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        clock = ui.serif("", 40f, bold = true).apply { includeFontPadding = false }
        ui.tappable(clock!!, { openAlarms() })
        timeRow.addView(clock, ui.lp(0, weight = 1f))
        // Beside the clock: the small things you glance at or flick, right-aligned in a column
        // so a second line can sit under the first without pushing the clock about.
        val wrap = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val side = ui.vertical().apply { gravity = Gravity.END }
        val glance = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        // The ringer, one tap per step: ring → vibrate → silent. Padded, since the word is small.
        ringer = ui.mono("", 13f, p.soft).apply { setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)) }
        ui.tappable(ringer!!, {
            Ringer.cycle(host)
            updateRinger()
        })
        glance.addView(ringer, wrap())
        battery = ui.mono("", 13f, p.soft)
        ui.tappable(battery!!, { openBatterySettings() })
        glance.addView(battery, wrap())
        side.addView(glance, wrap())
        timeRow.addView(side, wrap())
        col.addView(timeRow)

        if (style != StatusBarStyle.MINIMAL) {
            date = ui.mono("", 13f, p.text)
            ui.tappable(date!!, { openCalendar() })
            col.addView(date, ui.lp().apply { topMargin = ui.dp(4) })
        }

        if (style == StatusBarStyle.FULL) {
            val infoRow = ui.horizontal()
            weatherLabel = ui.mono("", 12f, p.soft)
            ui.tappable(weatherLabel!!, { openWeather() })
            alarmLabel = ui.mono("", 12f, p.soft).apply { gravity = Gravity.END }
            ui.tappable(alarmLabel!!, { openAlarms() })
            infoRow.addView(weatherLabel, ui.lp(0, weight = 1f))
            infoRow.addView(alarmLabel, ui.lp(0, weight = 1f))
            col.addView(infoRow, ui.lp().apply { topMargin = ui.dp(8) })
        }

        col.setOnLongClickListener {
            HomeMenu.show(host)
            true
        }
        col.contentDescription = "Status. Long-press for settings."
        updateStatus()
        return ui.vertical().apply {
            addView(col)
            addView(ui.rule())
        }
    }

    private fun updateStatus() {
        val now = Date()
        clock?.text = DateFormat.format(if (DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm", now)
        date?.text = DateFormat.format("EEE d MMM", now).toString().uppercase(Locale.getDefault())
        battery?.text = batteryPercent(host)?.let { "$it%" } ?: ""
        updateRinger()
        alarmLabel?.let { label ->
            val text = nextAlarmText()
            label.text = text ?: ""
            label.visibility = if (text == null) View.GONE else View.VISIBLE
        }
        weatherLabel?.let { label ->
            Weather.current { state ->
                label.text = state?.line ?: ""
                label.visibility = if (state == null) View.GONE else View.VISIBLE
            }
        }
    }

    /** Also called when the mode changes elsewhere (volume keys, the tile): see [HomeActivity]. */
    private fun updateRinger() {
        val label = ringer ?: return
        val mode = Ringer.mode(host)
        label.text = mode.short
        label.contentDescription = "Ringer: ${mode.label}. Tap to change."
    }

    private fun nextAlarmText(): String? {
        val info = host.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val fmt = if (DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm a"
        return "Alarm " + DateFormat.format(fmt, info.triggerTime)
    }

    /**
     * The clock app's alarm list. Some clocks refuse SHOW_ALARMS from a caller they don't trust
     * (AOSP's wants the SET_ALARM permission, others don't export the handler at all), and the
     * refusal arrives as an exception, not as "no such app". So when it fails, find out who would
     * have answered and open that app by its icon instead: the clock, one tap from its alarms.
     */
    private fun openAlarms() {
        val show = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        if (PhoneCheck.open(host, show)) return
        val pm = host.packageManager
        val launch = runCatching { pm.queryIntentActivities(show, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
            .mapNotNull { it.activityInfo?.packageName }.distinct()
            .mapNotNull { pkg -> pm.getLaunchIntentForPackage(pkg) }
        if (!PhoneCheck.open(host, *launch.toTypedArray())) {
            Toast.makeText(host, "No clock app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCalendar() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        if (!PhoneCheck.open(host, intent)) Toast.makeText(host, "No calendar app found", Toast.LENGTH_SHORT).show()
    }

    private fun openBatterySettings() {
        if (!PhoneCheck.open(host, Intent(Intent.ACTION_POWER_USAGE_SUMMARY), Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) {
            Toast.makeText(host, "Open Settings → Battery", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWeather() {
        if (!PhoneCheck.open(host, Intent(Intent.ACTION_VIEW, Uri.parse(Weather.WEB_URL)))) {
            Toast.makeText(host, "No browser found on this device", Toast.LENGTH_SHORT).show()
        }
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
