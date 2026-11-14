package app.readfirst.ui

import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.data.FlipTimer

/**
 * The reading / leisure timer: choose what it is for and how long, then start it now or have it
 * start when the phone is put face-down. While one is armed or running the same sheet shows the
 * time left and a way to stop it. The countdown itself belongs to TorchLevels ([FlipTimer]); if
 * that app isn't installed the sheet's entry points are hidden and this is never reached.
 */
object TimerSheet {
    private val PRESETS = listOf(10, 15, 25, 45, 60)
    private const val MAX_MINUTES = 600

    fun show(host: HomeActivity) {
        if (!FlipTimer.isAvailable(host)) {
            Toast.makeText(host, "The timer needs the TorchLevels app", Toast.LENGTH_SHORT).show()
            return
        }
        // Draw what was last known at once, then correct it if TorchLevels says otherwise.
        val shown = FlipTimer.cached
        val content = render(host, shown)
        FlipTimer.refresh(host) { now -> if (content.isAttachedToWindow && now.phase != shown.phase) render(host, now) }
    }

    private fun render(host: HomeActivity, state: FlipTimer.State): View {
        val content = if (state.active) running(host, state) else setup(host)
        host.showSheet(content)
        // Coming back to the app with the sheet still open: the countdown paused with the screen,
        // and the timer may have finished meanwhile. Ask again and redraw.
        if (state.active) host.sheetRefresh = { FlipTimer.refresh(host) { now -> if (content.isAttachedToWindow) render(host, now) } }
        return content
    }

    // ---- choosing -----------------------------------------------------------------------------

    private fun setup(host: HomeActivity): View {
        val ui = host.ui
        val p = ui.p
        val prefs = host.prefs
        var purpose = if (prefs.timerLabel == FlipTimer.LEISURE) FlipTimer.LEISURE else FlipTimer.READING
        var minutes = prefs.timerMinutes

        val col = ui.vertical().apply {
            setPadding(0, 0, 0, ui.dp(16))
            // Somewhere for focus to rest other than the minutes box, so opening the sheet
            // never brings the keyboard up by itself.
            isFocusableInTouchMode = true
        }
        col.addView(ui.mono("Timer", 11f).also { ui.margins(it, 18, 16, 18, 14) })

        /** A choice that is on is filled in, like the on half of a toggle; no re-drawn sheet. */
        fun mark(button: TextView, on: Boolean) {
            button.setTextColor(if (on) p.bg else p.text)
            button.background = if (on) ui.box(p.text) else StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ui.box(p.pressed))
                addState(intArrayOf(), ui.box(0))
            }
        }

        col.addView(ui.mono("For", 10f, p.soft).also { ui.margins(it, 18, 0, 18, 6) })
        val purposes = ArrayList<Pair<String, TextView>>()
        val purposeRow = ui.horizontal()
        listOf(FlipTimer.READING, FlipTimer.LEISURE).forEachIndexed { i, name ->
            val button = ui.boxButton(name) {
                purpose = name
                purposes.forEach { (n, b) -> mark(b, n == purpose) }
            }
            purposes += name to button
            purposeRow.addView(button, ui.lp(0, weight = 1f).apply { if (i > 0) leftMargin = ui.dp(6) })
        }
        purposes.forEach { (n, b) -> mark(b, n == purpose) }
        col.addView(purposeRow)
        ui.margins(purposeRow, 18, 0, 18, 16)

        col.addView(ui.mono("Minutes", 10f, p.soft).also { ui.margins(it, 18, 0, 18, 6) })
        val presets = ArrayList<Pair<Int, TextView>>()
        val custom = EditText(host).apply {
            setTextColor(p.text)
            textSize = 15f
            hint = "Other: 1 to $MAX_MINUTES"
            setHintTextColor(p.soft)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = ui.box(0)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        fun hideKeyboard() {
            custom.clearFocus()
            host.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(custom.windowToken, 0)
        }
        /** A typed length wins over the buttons; null while it is not a usable number. */
        fun typed(): Int? = custom.text.toString().trim().toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }
        fun markPresets() = presets.forEach { (m, b) -> mark(b, custom.text.isNullOrBlank() && m == minutes) }

        val presetRow = ui.horizontal()
        PRESETS.forEachIndexed { i, m ->
            val button = ui.boxButton("$m") {
                minutes = m
                custom.setText("")
                hideKeyboard()
                markPresets()
            }
            presets += m to button
            presetRow.addView(button, ui.lp(0, weight = 1f).apply { if (i > 0) leftMargin = ui.dp(4) })
        }
        col.addView(presetRow)
        ui.margins(presetRow, 18, 0, 18, 8)

        // The length used last time comes back: on its button, or in the box if it was typed.
        if (minutes !in PRESETS) custom.setText("$minutes")
        custom.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = markPresets()
        })
        custom.setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        markPresets()
        col.addView(custom)
        ui.margins(custom, 18, 0, 18, 16)

        var busy = false
        fun begin(onFlip: Boolean) {
            if (busy) return
            val length = if (custom.text.isNullOrBlank()) minutes else typed()
            if (length == null || length !in 1..MAX_MINUTES) {
                Toast.makeText(host, "Choose 1 to $MAX_MINUTES minutes", Toast.LENGTH_SHORT).show()
                return
            }
            busy = true
            hideKeyboard()
            FlipTimer.startAsync(host, length, purpose, onFlip) { ok, _ ->
                busy = false
                if (!ok) {
                    Toast.makeText(host, "TorchLevels didn't start the timer", Toast.LENGTH_LONG).show()
                    return@startAsync
                }
                // Remembered only once it worked, and after the answer: saving redraws Home.
                prefs.timerLabel = purpose
                prefs.timerMinutes = length
                if (onFlip) Toast.makeText(host, "Put the phone face-down to start $length min", Toast.LENGTH_LONG).show()
                // Back to Home, where the status row now counts down. Unless the user already
                // moved on to another sheet: that one isn't ours to close.
                if (col.isAttachedToWindow) host.dismissSheet()
            }
        }
        val now = ui.boxButton("Start now") { begin(onFlip = false) }
        col.addView(now.apply { setBackgroundColor(p.text); setTextColor(p.bg) })
        ui.margins(now, 18, 0, 18, 0)
        val flip = ui.boxButton("Start when face-down") { begin(onFlip = true) }
        col.addView(flip)
        ui.margins(flip, 18, 10, 18, 0)

        liftAboveKeyboard(col, ui.dp(16))
        return col
    }

    /**
     * The sheet sits on the bottom edge, which is exactly where the keyboard opens. Where the
     * window is drawn edge to edge (Android 15+) nothing moves it out of the way, so the sheet
     * pads itself by however much of the keyboard reaches above the navigation bar. Older
     * versions resize the window themselves and report no keyboard here, so this adds nothing.
     */
    private fun liftAboveKeyboard(col: View, basePadding: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        col.setOnApplyWindowInsetsListener { v, insets ->
            val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, basePadding + (keyboard - bars).coerceAtLeast(0))
            insets
        }
    }

    // ---- armed or running ---------------------------------------------------------------------

    private fun running(host: HomeActivity, shown: FlipTimer.State): View {
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply { setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16)) }
        col.addView(ui.mono("Timer · ${shown.label.ifBlank { "Running" }}", 11f))
        val big = ui.serif("", 44f, bold = true).apply { includeFontPadding = false }
        col.addView(big.also { ui.margins(it, t = 12) })
        val detail = ui.mono("", 11f, p.soft)
        col.addView(detail.also { ui.margins(it, t = 6) })

        fun draw(s: FlipTimer.State) {
            when (s.phase) {
                FlipTimer.Phase.RUNNING -> {
                    big.text = FlipTimer.clock(s.remainingMs())
                    val fmt = if (DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm a"
                    detail.text = "Ends at ${DateFormat.format(fmt, s.endsAt)}".uppercase()
                }
                FlipTimer.Phase.ARMED -> {
                    big.text = "Ready"
                    detail.text = "Starts when the phone is face-down".uppercase()
                }
                FlipTimer.Phase.RINGING -> {
                    big.text = "Time's up"
                    detail.text = "Stop to silence it".uppercase()
                }
                FlipTimer.Phase.IDLE -> {}
            }
        }
        draw(shown)

        var busy = false
        val stop = ui.boxButton("Stop") {
            if (busy) return@boxButton
            busy = true
            FlipTimer.cancelAsync(host) { ok, _ ->
                busy = false
                if (!ok) Toast.makeText(host, "TorchLevels didn't stop the timer", Toast.LENGTH_LONG).show()
                else if (col.isAttachedToWindow) host.dismissSheet()
            }
        }
        col.addView(stop)
        ui.margins(stop, t = 20)

        // Counts down once a second, only while the sheet is on screen. TorchLevels moves the
        // timer on by itself (flipped: armed → running; finished: running → ringing → idle), so
        // while it isn't simply running, ask it again every few seconds and redraw on a change.
        var ticks = 0
        val tick = object : Runnable {
            override fun run() {
                if (!host.isVisible) return
                val s = FlipTimer.cached
                if (s.phase != shown.phase || s.label != shown.label) {
                    render(host, s)
                    return
                }
                draw(s)
                val waiting = s.phase != FlipTimer.Phase.RUNNING || s.remainingMs() == 0L
                if (waiting && ++ticks % 3 == 0) FlipTimer.refresh(host)
                col.postDelayed(this, if (waiting) 1000L else s.remainingMs() % 1000 + 20)
            }
        }
        col.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.post(tick) }
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(tick) }
        })
        return col
    }
}
