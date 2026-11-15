package app.readfirst.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.data.BookEntry
import app.readfirst.data.FlipTimer
import app.readfirst.data.NowPlaying
import app.readfirst.data.PhoneCheck
import app.readfirst.data.Ringer
import app.readfirst.data.StatusBarStyle
import app.readfirst.data.Weather
import java.util.Date

/**
 * Home: the book you're reading and one tap to continue, with everything else quiet. The cover is
 * the one focal point: a big clock, a timer button, a date and a tiny battery above it, and only
 * when something is playing (or is genuinely wrong with the phone) a card below it. The ringer is
 * a slim slider on the right edge ([RingerSlider]).
 */
class HomeScreen(host: HomeActivity) : Screen(host) {
    override val isHome = true
    private var clock: TextView? = null
    private var date: TextView? = null
    private var battery: BatteryIcon? = null
    private var timerButton: TimerButton? = null
    private var ringerSlider: RingerSlider? = null
    private var weatherLabel: TextView? = null
    private var alarmLabel: TextView? = null
    private var listenBar: SegmentBar? = null

    /** Side margin, in dp, of the content: 24 (the grid's gutter). Blocks that run under the ringer
     *  slider's reach keep a wider margin on the right so nothing meets its knob. */
    private val gutter = 24
    private val endGutter get() = if (host.prefs.ringerSlider) 48 else gutter

    override fun build(): View {
        val frame = GestureFrame(host).apply {
            onSwipeDown = { expandNotifications(host) }
            onLongPress = { HomeMenu.show(host) }
        }
        val col = ui.vertical()
        col.addView(header())

        HomeNotice.build(host)?.let { col.addView(it) }
        val book = host.library.current()
        val listening = NowPlaying.get(host).current(host.prefs.nowPlayingAllAudio)
        listenBar = null
        if (book == null && listening == null) col.addView(emptyState())
        else if (book != null) col.addView(readingBlock(book))
        if (listening != null) col.addView(nowListening(listening))
        col.addView(View(host), ui.lp(h = ui.dp(24)))

        // The dock (pinned apps) is added by Pager, below this page. No weighted filler in here: it
        // would swallow the overflow, leaving the column exactly one screen tall and clipped.
        val scroll = ScrollView(host).apply { addView(col) }
        frame.contentScrolls = { direction -> scroll.canScrollVertically(direction) }
        frame.addView(scroll)

        val root = FrameLayout(host)
        root.addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ringerSlider = null
        if (host.prefs.ringerSlider) {
            // A sibling of the scrolling content, never inside it: it travels with this page and
            // stays put while the page scrolls. 34% down, thumb-reachable and clear of the clock.
            val slider = RingerSlider(host)
            ringerSlider = slider
            root.addView(slider, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP))
            root.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
                val h = (b - t).toFloat()
                slider.translationY = minOf(h * 0.34f, h - slider.measuredHeight - ui.dp(8)).coerceAtLeast(0f)
            }
            slider.sync(Ringer.mode(host), Ringer.hasAccess(host))
        }
        return root
    }

    override fun onHide() {
        ticker.removeCallbacks(timerTick)
    }

    override fun onTimeTick() {
        updateStatus()
        updateListeningProgress()
    }

    /** Back in front with nothing rebuilt: the clock, the battery and the timer may all have moved. */
    override fun onShown() {
        onTimeTick()
        if (timerButton != null) {
            FlipTimer.refresh(host) { startTimerTick() }
            startTimerTick()
        }
    }

    // ---- header -------------------------------------------------------------------------------

    /**
     * The clock (48sp serif) with the round timer button beside it, and under it the date and a
     * tiny battery. Everything opens what it is about: the clock app, the calendar, battery
     * settings. Long-press anywhere still opens the Home menu.
     */
    private fun header(): View {
        val style = host.prefs.statusBarStyle
        val menu = { HomeMenu.show(host) }
        val col = ui.vertical().apply { setPadding(ui.dp(gutter), ui.dp(16), ui.dp(gutter), 0) }

        val timeRow = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        clock = ui.text("", 48f, p.text, Fonts.serifBold)
        ui.tappable(clock!!, { openAlarms() }, menu)
        timeRow.addView(clock, ui.lp(0, weight = 1f))
        // The reading / leisure timer is kept by a companion app: no button where that isn't installed.
        timerButton = if (FlipTimer.isAvailable(host)) TimerButton(host, p).also { button ->
            ui.tappable(button, { TimerSheet.show(host) }, menu)
            button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = startTimerTick()
                override fun onViewDetachedFromWindow(v: View) { if (timerButton === v) ticker.removeCallbacks(timerTick) }
            })
            timeRow.addView(button, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        } else null
        // The label draws from what was last heard; ask again, since the timer moves on by itself.
        if (timerButton != null) FlipTimer.refresh(host) { startTimerTick() }
        col.addView(timeRow)

        val dateRow = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        date = null
        if (style != StatusBarStyle.MINIMAL) {
            date = ui.text("", 13f, p.soft).apply { setPadding(0, ui.dp(10), ui.dp(4), ui.dp(10)) }
            ui.tappable(date!!, { openCalendar() }, menu)
            dateRow.addView(date, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        battery = BatteryIcon(host, p).also { icon ->
            ui.tappable(icon, { openBatterySettings() }, menu)
            dateRow.addView(icon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = ui.dp(8) })
        }
        col.addView(dateRow, ui.lp().apply { topMargin = ui.dp(-6) })

        weatherLabel = null
        alarmLabel = null
        if (style == StatusBarStyle.FULL) {
            val infoRow = ui.horizontal()
            weatherLabel = ui.text("", 13f, p.soft)
            ui.tappable(weatherLabel!!, { openWeather() }, menu)
            alarmLabel = ui.text("", 13f, p.soft).apply { gravity = Gravity.END }
            ui.tappable(alarmLabel!!, { openAlarms() }, menu)
            infoRow.addView(weatherLabel, ui.lp(0, weight = 1f))
            infoRow.addView(alarmLabel, ui.lp(0, weight = 1f))
            col.addView(infoRow, ui.lp().apply { topMargin = ui.dp(4) })
        }

        col.contentDescription = "Status"
        updateStatus()
        return col
    }

    private fun updateStatus() {
        val now = Date()
        clock?.text = DateFormat.format(if (DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm", now)
        date?.text = DateFormat.format("EEE d MMM", now).toString()
        battery?.setLevel(batteryPercent(host))
        ringerSlider?.sync(Ringer.mode(host), Ringer.hasAccess(host))
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

    private val ticker = Handler(Looper.getMainLooper())
    private var timerPolls = 0

    /**
     * The timer button's digits. It redraws once a second (once a minute in Black ink), but only
     * while a timer is armed or running and only while Home is on screen. One chain at a time:
     * [startTimerTick] always cancels the last before starting.
     */
    private val timerTick = object : Runnable {
        override fun run() {
            val button = timerButton ?: return
            val s = FlipTimer.cached
            button.update(s)
            if (!s.active || !host.isVisible || !button.isAttachedToWindow) return
            // Waiting on TorchLevels (to be flipped, to stop ringing, to say it has finished):
            // only it knows when that happens, so ask every few seconds.
            val waiting = s.phase != FlipTimer.Phase.RUNNING || s.remainingMs() == 0L
            if (waiting && ++timerPolls % 3 == 0) FlipTimer.refresh(host) { startTimerTick() }
            ticker.postDelayed(this, if (waiting) (if (p.isBlack) 5000L else 1000L) else TimerDigits.nextChangeMs(s.remainingMs(), p.isBlack))
        }
    }

    private fun startTimerTick() {
        ticker.removeCallbacks(timerTick)
        timerTick.run()
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

    private fun openWeather() = WeatherSheet.show(host)

    // ---- reading ------------------------------------------------------------------------------

    /**
     * The cover (120x180) with title, author and progress bar beside it, and Continue under it.
     * Tap the cover or title to open the book, long-press for its actions. The right column keeps
     * a 32dp end margin so the title never meets the ringer knob.
     */
    private fun readingBlock(book: BookEntry): View {
        val opened = book.openedAt > 0
        val block = ui.vertical().apply { setPadding(ui.dp(gutter), ui.dp(40), ui.dp(endGutter), 0) }

        val row = ui.horizontal().apply { gravity = Gravity.TOP }
        row.addView(CoverView(host, p, book), LinearLayout.LayoutParams(ui.dp(120), ui.dp(180)))
        val side = ui.vertical()
        side.addView(ui.serif(book.title, 20f, bold = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.2f)
        })
        if (book.author.isNotBlank()) side.addView(ui.text(book.author, 13f, p.soft).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }.also { ui.margins(it, t = 4) })
        side.addView(View(host), ui.lp(h = 0, weight = 1f))
        side.addView(SegmentBar(host, p, book.progress), ui.lp())
        // 16 between cover and text, 32 kept clear of the slider's reach (see the class comment).
        row.addView(side, LinearLayout.LayoutParams(0, ui.dp(180), 1f).apply {
            leftMargin = ui.dp(16)
            rightMargin = ui.dp((32 - (endGutter - gutter)).coerceAtLeast(0))
        })
        ui.tappable(row, { host.openBook(book) }, { BookActions.show(host, book) })
        row.contentDescription = book.title
        block.addView(row)

        block.addView(ui.button(if (opened) "Continue" else "Start", filled = true) { host.openBook(book) },
            ui.lp().apply { topMargin = ui.dp(16) })
        return block
    }

    private fun emptyState(): View {
        val block = ui.vertical().apply { setPadding(ui.dp(gutter), ui.dp(40), ui.dp(endGutter), 0) }
        block.addView(ui.serif("Your shelf is empty", 20f, bold = true))
        block.addView(ui.button("Get free books", filled = true) { Starter.show(host) }, ui.lp().apply { topMargin = ui.dp(24) })
        block.addView(ui.button("Add my books") { AddBooks.show(host) }, ui.lp().apply { topMargin = ui.dp(8) })
        return block
    }

    // ---- listening ----------------------------------------------------------------------------

    /** What an audiobook app is playing, with its own transport controls. Shown only while audio
     *  is active; long-press for the full sheet. */
    private fun nowListening(s: NowPlaying.State): View {
        val np = NowPlaying.get(host)
        val col = ui.vertical().apply { setPadding(ui.dp(gutter), ui.dp(32), ui.dp(endGutter), 0) }

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
        row.addView(names, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(16) })
        ui.tappable(row, { np.open(host, s) }, { NowListeningSheet.show(host, s) })
        col.addView(row)

        if (s.durationMs > 0) {
            val bar = SegmentBar(host, p, s.fraction)
            if (s.canSeek) bar.onSeek = { fraction -> np.seekTo(s, fraction) }
            listenBar = bar
            col.addView(bar, ui.lp().apply { topMargin = ui.dp(12) })
        }

        val skip = host.prefs.audioSkipMs
        val controls = ui.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        fun cell(view: View, description: String, onClick: () -> Unit) {
            view.contentDescription = description
            ui.tappable(view, onClick)
            controls.addView(view, ui.lp(0, ui.dp(48), 1f))
        }
        fun skipLabel(text: String) = ui.text(text, 14f, p.text, Fonts.sansBold).apply { gravity = Gravity.CENTER }
        cell(skipLabel("−${skip / 1000}s"), "Back ${skip / 1000} seconds") { np.jump(s, -skip.toLong()) }
        cell(PlayGlyph(host, p, s.playing), if (s.playing) "Pause" else "Play") { np.togglePlay(s) }
        cell(skipLabel("+${skip / 1000}s"), "Forward ${skip / 1000} seconds") { np.jump(s, skip.toLong()) }
        col.addView(controls, ui.lp().apply { topMargin = ui.dp(4) })
        return col
    }

    private fun updateListeningProgress() {
        val bar = listenBar ?: return
        val s = NowPlaying.get(host).current(host.prefs.nowPlayingAllAudio) ?: return
        bar.fraction = s.fraction
        bar.invalidate()
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

/** A play triangle or a pause pair, drawn: the transport button of the listening card. */
private class PlayGlyph(context: Context, private val p: InkPalette, private val playing: Boolean) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = p.text }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (playing) {
            canvas.drawRect(cx - 7f * d, cy - 8f * d, cx - 2f * d, cy + 8f * d, paint)
            canvas.drawRect(cx + 2f * d, cy - 8f * d, cx + 7f * d, cy + 8f * d, paint)
        } else {
            path.reset()
            path.moveTo(cx - 6f * d, cy - 9f * d)
            path.lineTo(cx + 8f * d, cy)
            path.lineTo(cx - 6f * d, cy + 9f * d)
            path.close()
            canvas.drawPath(path, paint)
        }
    }
}
