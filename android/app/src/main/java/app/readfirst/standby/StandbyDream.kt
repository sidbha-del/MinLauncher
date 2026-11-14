package app.readfirst.standby

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.readfirst.book.Progress
import app.readfirst.data.FlipTimer
import app.readfirst.data.Library
import app.readfirst.data.Page
import app.readfirst.data.PageStyle
import app.readfirst.data.Prefs
import app.readfirst.data.Texture
import app.readfirst.data.Weather
import app.readfirst.ui.Fonts
import app.readfirst.ui.InkPalette
import app.readfirst.ui.SegmentBar
import app.readfirst.ui.Ui
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * StandBy: ReadFirst as the phone's screen saver (Settings → Display → Screen saver), for when it
 * is charging on a desk or by the bed. A large clock, and beside or below it the few things worth
 * a glance: date, battery, next alarm, weather, a running timer, and the book you are in.
 *
 * Three things shape it:
 * - It is drawn for an OLED screen left on for hours. The page is true black, so unlit pixels
 *   are off, and the whole layout moves a few dp every minute so that no pixel stays lit in one
 *   place ([shift]); nothing is drawn outside that moving block.
 * - In a dark room (the light sensor reads under [NIGHT_LUX]) the ink turns a dim red and the
 *   screen is allowed to dim, so it can share a bedroom.
 * - It does nothing between minutes. No animation, no seconds; a touch ends it.
 *
 * It reads the same sources Home does and needs no permission of its own.
 */
class StandbyDream : DreamService(), SensorEventListener {
    private val main = Handler(Looper.getMainLooper())
    private var frame: StandbyFrame? = null
    private var content: View? = null

    private var running = false
    private var night = false
    /** Minutes shown so far, from a random start so each session begins somewhere else. */
    private var step = Random.nextInt(SHIFT_X_PERIOD * SHIFT_Y_PERIOD)
    private var weather: String? = null
    private var timer: FlipTimer.State = FlipTimer.cached

    private var clock: TextView? = null
    private var date: TextView? = null
    private var battery: TextView? = null
    private var alarm: TextView? = null
    private var weatherLine: TextView? = null
    private var timerLine: TextView? = null

    /** The minute, and the battery. Registered only while dreaming. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BATTERY_CHANGED) show(battery, batteryText(i)) else onMinute()
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    /** The dream's window exists from here on, and not before: this is where content is set. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false // any touch ends the dream; nothing on it is a control
        isFullscreen = true
        isScreenBright = true
        hideSystemBars()
        val f = StandbyFrame(this) { rebuild() }
        f.setBackgroundColor(Color.BLACK)
        val margin = dp(MARGIN_DP + SHIFT_DP)
        f.setPadding(margin, margin, margin, margin)
        f.setOnApplyWindowInsetsListener { v, insets ->
            // Keep clear of the notch and of any bar the system still draws, then of the edge,
            // with room for the content to move by the burn-in shift without being cut off.
            val safe = safeInsets(insets)
            val before = intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
            v.setPadding(safe[0] + margin, safe[1] + margin, safe[2] + margin, safe[3] + margin)
            if (!before.contentEquals(intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom))) v.post { rebuild() }
            insets
        }
        frame = f
        setContentView(f)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (running) return
        running = true
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        val sensors = getSystemService(SensorManager::class.java)
        // No light sensor (some tablets): it simply stays in its normal ink.
        sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rebuild()
        askOutside()
    }

    override fun onDreamingStopped() {
        stop()
        super.onDreamingStopped()
    }

    /** Android doesn't promise onDreamingStopped before this on every path; stopping twice is safe. */
    override fun onDetachedFromWindow() {
        stop()
        frame = null
        content = null
        super.onDetachedFromWindow()
    }

    private fun stop() {
        if (!running) return
        running = false
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(SensorManager::class.java)?.unregisterListener(this) }
        main.removeCallbacksAndMessages(null)
        pending = false
        firstReading = true
        night = false
    }

    /** Turned on its side, or back. The frame notices its own size change too; this covers the rest. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        frame?.post { rebuild() }
    }

    // ---- night --------------------------------------------------------------------------------

    private var firstReading = true
    private var wanted = false
    private var pending = false
    private val applyNight = Runnable {
        pending = false
        setNight(wanted)
    }

    /**
     * Dark below [NIGHT_LUX], light again only above [DAY_LUX]: the gap stops it flickering at
     * the threshold, and a change has to last a couple of seconds, so a hand passing over the
     * sensor (or a car's headlights) doesn't flip the whole screen. The first reading counts at
     * once, so a dream that starts in the dark starts red.
     */
    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values.firstOrNull() ?: return
        val dark = if (night) lux < DAY_LUX else lux < NIGHT_LUX
        if (firstReading) {
            firstReading = false
            setNight(dark)
            return
        }
        if (dark == night) {
            // Back to what is showing before the change took hold: forget it.
            main.removeCallbacks(applyNight)
            pending = false
        } else if (!pending) {
            wanted = dark
            pending = true
            main.postDelayed(applyNight, 2000)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setNight(on: Boolean) {
        if (!running || on == night) return
        night = on
        // Not "bright" lets the system dim the screen as it would any idle one.
        isScreenBright = !on
        rebuild()
    }

    /**
     * The app's own dark page for the user's ink (Night), on true black instead of Night's very
     * dark brown; or, at night, one dim red for everything. Red is the colour that least spoils
     * dark-adapted eyes, and on OLED a dim red pixel is nearly an unlit one.
     */
    private fun palette(): InkPalette {
        val base = InkPalette.of(Prefs.get(this).ink, PageStyle(Page.NIGHT, Texture.NONE))
        val black = Color.BLACK
        if (night) return InkPalette(
            base.ink, base.style, black, text = NIGHT_RED, soft = NIGHT_RED_SOFT,
            rule = NIGHT_RED, ruleSoft = NIGHT_RED_SOFT, fill = NIGHT_RED, warn = NIGHT_RED, pressed = 0,
        )
        return InkPalette(
            base.ink, base.style, black, text = base.text, soft = base.soft,
            rule = base.rule, ruleSoft = InkPalette.blend(base.text, black, 0.16f), fill = base.fill, warn = base.warn, pressed = 0,
        )
    }

    // ---- layout -------------------------------------------------------------------------------

    /**
     * Builds everything again for the space there is now: two panes side by side when the screen
     * is wider than tall (clock left, details right), one column otherwise. Called for a new
     * size, a turn, and the switch between day and night ink; it is a dozen views, so starting
     * over is simpler than restyling.
     */
    private fun rebuild() {
        val f = frame ?: return
        val w = f.width - f.paddingLeft - f.paddingRight
        val h = f.height - f.paddingTop - f.paddingBottom
        if (w <= 0 || h <= 0) return // not laid out yet; the frame calls back when it is
        val p = palette()
        val ui = Ui(this, p)
        val landscape = w > h
        val gap = ui.dp(28)
        val paneWidth = if (landscape) (w - gap) / 2 else w
        val align = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val clockPane = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        clock = ui.text("", 10f, p.text, Fonts.serifBold).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, clockSize(paneWidth, if (landscape) h * 0.6f else h * 0.28f))
            maxLines = 1
            gravity = Gravity.CENTER
        }
        clockPane.addView(clock, LinearLayout.LayoutParams(wrap, wrap))
        date = ui.mono("", 15f, p.soft).apply { gravity = Gravity.CENTER }
        clockPane.addView(date, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = ui.dp(6) })

        val details = ui.vertical()
        fun line(sizeSp: Float, color: Int, top: Int): TextView = ui.mono("", sizeSp, color).apply {
            gravity = align
            maxLines = 2
            details.addView(this, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ui.dp(top) })
        }
        battery = line(15f, p.text, 0)
        alarm = line(15f, p.text, 12)
        weatherLine = line(15f, p.text, 12)
        timerLine = line(15f, p.text, 12)

        Library.get(this).current()?.let { book ->
            details.addView(ui.mono(if (book.openedAt > 0) "Now reading" else "Up next", 11f, p.soft).apply { gravity = align },
                LinearLayout.LayoutParams(match, wrap).apply { topMargin = ui.dp(26) })
            details.addView(ui.serif(book.title, 21f, bold = true).apply {
                gravity = align
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ui.dp(8) })
            details.addView(SegmentBar(this, p, book.progress), LinearLayout.LayoutParams(match, wrap).apply { topMargin = ui.dp(12) })
            val parts = ArrayList<String>()
            parts += "${Progress.percent(book.progress)}%"
            if (book.pages > 0) parts += "p. ${book.page + 1}/${book.pages}"
            if (book.author.isNotBlank()) parts += book.author.substringBefore(',')
            details.addView(ui.mono(parts.joinToString(" · "), 12f, p.soft).apply {
                gravity = align
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ui.dp(8) })
        }

        val root = (if (landscape) ui.horizontal() else ui.vertical()).apply { gravity = Gravity.CENTER }
        if (landscape) {
            root.addView(clockPane, LinearLayout.LayoutParams(0, wrap, 1f))
            root.addView(details, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = gap })
        } else {
            root.addView(clockPane, LinearLayout.LayoutParams(match, wrap))
            root.addView(details, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ui.dp(40) })
        }
        f.removeAllViews()
        f.addView(root, FrameLayout.LayoutParams(match, match))
        content = root
        fill()
        shift()
    }

    /** The largest serif that fits five characters across the pane, within [maxHeight]. */
    private fun clockSize(maxWidth: Int, maxHeight: Float): Float {
        val paint = TextPaint().apply { typeface = Fonts.serifBold; textSize = 100f }
        val widthPerPx = paint.measureText("00:00") / 100f
        return min(maxWidth * 0.94f / widthPerPx, maxHeight).coerceAtLeast(dp(40).toFloat())
    }

    /**
     * Burn-in protection. The whole block steps along two triangle waves of different, co-prime
     * lengths, so it wanders across a square of ±[SHIFT_DP] and only returns to a spot after
     * [SHIFT_X_PERIOD] × [SHIFT_Y_PERIOD] minutes. Each minute moves it about five dp, more than
     * the stroke of anything but the clock's digits, and those change by themselves.
     */
    private fun shift() {
        val view = content ?: return
        fun wave(n: Int, period: Int): Float = 4f * abs((n % period).toFloat() / period - 0.5f) - 1f
        val reach = dp(SHIFT_DP).toFloat()
        view.translationX = wave(step, SHIFT_X_PERIOD) * reach
        view.translationY = wave(step + 3, SHIFT_Y_PERIOD) * reach
    }

    // ---- content ------------------------------------------------------------------------------

    private fun onMinute() {
        step++
        fill()
        shift()
        askOutside()
    }

    private fun fill() {
        val now = Date()
        clock?.text = DateFormat.format(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm", now)
        date?.text = DateFormat.format("EEEE d MMMM", now).toString().uppercase(Locale.getDefault())
        show(battery, batteryText(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
        show(alarm, alarmText())
        show(weatherLine, weather)
        show(timerLine, timerText())
    }

    /** A line with nothing to say takes no room. */
    private fun show(view: TextView?, text: String?) {
        view ?: return
        view.text = text.orEmpty().uppercase(Locale.getDefault())
        view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    /**
     * The two things that come from outside the app: weather (cached, re-fetched at most every
     * half hour by [Weather]) and the timer TorchLevels keeps. Both answer later, on the main
     * thread, possibly after the dream has ended; hence the check.
     */
    private fun askOutside() {
        Weather.current { state ->
            if (running && state != null) {
                weather = state.line
                show(weatherLine, weather)
            }
        }
        if (!FlipTimer.isAvailable(this)) return
        FlipTimer.refresh(this) { state ->
            if (!running) return@refresh
            timer = state
            show(timerLine, timerText())
            // A timer ends between minutes; look again just after it should have.
            main.removeCallbacks(timerEnded)
            if (state.phase == FlipTimer.Phase.RUNNING) main.postDelayed(timerEnded, state.remainingMs() + 1500)
        }
    }

    private val timerEnded = Runnable { if (running) askOutside() }

    private fun batteryText(i: Intent?): String? {
        i ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val state = when {
            i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL -> " · Full"
            i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING -> " · Charging"
            i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 -> " · Plugged in"
            else -> ""
        }
        return "Battery ${level * 100 / scale}%$state"
    }

    private fun alarmText(): String? {
        val at = getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: return null
        val time = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        // Tomorrow morning's alarm needs no day; one further off does.
        val fmt = if (at - System.currentTimeMillis() < 24 * 3600_000L) time else "EEE $time"
        return "Alarm ${DateFormat.format(fmt, at)}"
    }

    /** To the minute, since that is how often this screen changes; the end time carries the rest. */
    private fun timerText(): String? {
        val s = timer
        val name = s.label.ifBlank { "Timer" }
        return when (s.phase) {
            FlipTimer.Phase.IDLE -> null
            FlipTimer.Phase.ARMED -> "$name · starts face-down"
            FlipTimer.Phase.RINGING -> "$name · time's up"
            FlipTimer.Phase.RUNNING -> {
                val left = s.remainingMs()
                if (left <= 0) return "$name · time's up"
                val time = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
                "$name · ${(left + 59_999) / 60_000} min left · ends ${DateFormat.format(time, s.endsAt)}"
            }
        }
    }

    // ---- window -------------------------------------------------------------------------------

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return // isFullscreen is enough there
        runCatching {
            window?.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** Left, top, right, bottom to stay out of: system bars and the display cutout. */
    @Suppress("DEPRECATION")
    private fun safeInsets(insets: WindowInsets): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return intArrayOf(i.left, i.top, i.right, i.bottom)
        }
        val out = intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.let { c ->
            out[0] = maxOf(out[0], c.safeInsetLeft)
            out[1] = maxOf(out[1], c.safeInsetTop)
            out[2] = maxOf(out[2], c.safeInsetRight)
            out[3] = maxOf(out[3], c.safeInsetBottom)
        }
        return out
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Below this the room is dark: red ink. Roughly a bedroom with the lights off. */
        private const val NIGHT_LUX = 5f
        /** Above this it is light again. Higher than [NIGHT_LUX] on purpose. */
        private const val DAY_LUX = 10f
        private const val NIGHT_RED = 0xFF9C2418.toInt()
        private const val NIGHT_RED_SOFT = 0xFF6E1A11.toInt()
        /** How far the content wanders from centre, each way. */
        private const val SHIFT_DP = 10
        private const val MARGIN_DP = 22
        private const val SHIFT_X_PERIOD = 7
        private const val SHIFT_Y_PERIOD = 11
    }
}

/**
 * The dream's root: black, and it reports when its size changes. That one signal covers a turn
 * of the phone however the system chooses to deliver it, and the first layout too.
 */
private class StandbyFrame(context: Context, private val onResize: () -> Unit) : FrameLayout(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Not during layout: rebuilding swaps this view's children.
        if (w > 0 && h > 0) post(onResize)
    }
}
