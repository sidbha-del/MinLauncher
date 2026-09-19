package app.booklauncher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.booklauncher.HomeActivity
import app.booklauncher.data.AppCatalog
import app.booklauncher.data.Focus
import kotlin.math.max

/**
 * The pause before a distracting app: a few seconds to choose, with "Read instead" as the easy
 * option. Only covers apps opened from this home screen (notifications and recents bypass it).
 */
object PauseSheet {
    private const val SECONDS = 5

    fun show(host: HomeActivity, entry: AppCatalog.Entry) {
        val ui = host.ui
        val p = ui.p
        val name = host.prefs.label(entry.key, entry.label)
        val col = ui.vertical().apply { setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(18)) }
        col.addView(ui.mono("A pause before $name", 11f))
        col.addView(ui.serif("Take a breath.", 26f, bold = true).also { ui.margins(it, t = 10) })
        val note = ui.text("", 15f, p.soft).apply { setLineSpacing(0f, 1.3f) }
        col.addView(note.also { ui.margins(it, t = 8) })
        val book = host.library.current()
        note.text = if (book != null) "Or read a page of ${book.title} instead?" else "Do you still want to open it?"
        Focus.day(host, 0) { d ->
            if (d.hasUsage && d.scrollingMs >= 60_000) note.text = "${Focus.format(d.scrollingMs)} on distracting apps today. " + note.text
        }

        if (book != null) {
            val read = ui.boxButton("Read instead") {
                Focus.recordResisted(host)
                host.openBook(book)
            }
            col.addView(read.apply { setBackgroundColor(p.text); setTextColor(p.bg) })
            ui.margins(read, t = 20)
        }
        val open = ui.boxButton("Open $name in $SECONDS") {}
        open.alpha = 0.45f
        col.addView(open)
        ui.margins(open, t = 10)
        val skip = ui.mono("Not now", 12f, p.text).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(16), 0, ui.dp(4))
            ui.tappable(this, {
                Focus.recordResisted(host)
                host.dismissSheet()
            })
        }
        col.addView(skip)

        var left = SECONDS
        val tick = object : Runnable {
            override fun run() {
                left--
                if (left > 0) {
                    open.text = "OPEN ${name.uppercase()} IN $left"
                    open.postDelayed(this, 1000)
                } else {
                    open.text = "OPEN ${name.uppercase()}"
                    open.alpha = 1f
                    open.setOnClickListener {
                        host.dismissSheet()
                        if (!host.catalog.launch(host, entry)) Toast.makeText(host, "Can't open $name", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        // The countdown stops if the sheet goes away (Home key, tap outside).
        open.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.postDelayed(tick, 1000) }
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(tick) }
        })
        host.showSheet(col)
    }
}

/** Choose which apps get the pause. Suggested feeds are pre-ticked; everything is changeable. */
class FocusAppsScreen(host: HomeActivity) : Screen(host) {
    override fun build(): View {
        val prefs = host.prefs
        val col = ui.vertical()
        col.addView(ui.topBar("Pause before apps", "", "Done", onRight = { host.pop() }))
        val list = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
        list.addView(ui.toggle("Pause on", "Off", prefs.pauseEnabled, { prefs.pauseEnabled = true }, { prefs.pauseEnabled = false })
            .also { ui.margins(it, 18, 14, 18, 8) })
        list.addView(ui.text(
            "Opening a ticked app from this home screen waits $PAUSE_SECONDS seconds and offers your book instead. " +
                "Notifications and recent apps still open directly.",
            13f, p.soft,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 4, 18, 10) })

        val chosen = Focus.distracting(prefs, host.catalog)
        val apps = host.catalog.apps.filter { it.key !in prefs.hidden }
        val suggested = apps.filter { it.component.packageName in Focus.SUGGESTED_DISTRACTING || it.component.packageName in chosen }
        val rest = apps - suggested.toSet()
        fun section(title: String, items: List<AppCatalog.Entry>) {
            if (items.isEmpty()) return
            list.addView(ui.group(title))
            for (a in items) list.addView(appRow(a, a.component.packageName in chosen))
        }
        section("Feeds and video", suggested)
        section("All other apps", rest)
        col.addView(ScrollView(host).apply { addView(list) }, ui.lp(h = 0, weight = 1f))
        return col
    }

    private fun appRow(a: AppCatalog.Entry, on: Boolean): View {
        val row = ui.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
        }
        val size = ui.dp(28)
        val icon = ImageView(host)
        row.addView(icon, LinearLayout.LayoutParams(size, size))
        host.catalog.loadIcon(a, size, p.ink, p.dark) { icon.setImageBitmap(it) }
        row.addView(ui.text(host.prefs.label(a.key, a.label), 15.5f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14) })
        row.addView(ui.mono(if (on) "✓ Pause" else "—", 11f, if (on) p.text else p.soft).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
            if (on) background = ui.box(0)
        }, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        ui.tappable(row, { Focus.setDistracting(host.prefs, host.catalog, a.component.packageName, !on) })
        return ui.vertical().apply {
            addView(row)
            addView(ui.rule(soft = true))
        }
    }

    companion object {
        const val PAUSE_SECONDS = 5
    }
}

/** A single bar split into Reading / Work / Scrolling / Other. Black ink uses patterns, not colours. */
class TimeSplitBar(context: Context, private val p: InkPalette) : View(context) {
    var day: Focus.Day? = null
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, d); color = p.text }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = max(1f, d); color = p.text }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (12 * d).toInt())

    override fun onDraw(canvas: Canvas) {
        val day = day ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(stroke.strokeWidth / 2, stroke.strokeWidth / 2, w - stroke.strokeWidth / 2, h - stroke.strokeWidth / 2, stroke)
        val total = day.totalMs.coerceAtLeast(1)
        var x = 0f
        val parts = listOf(day.readingMs to 0, day.workMs to 1, day.scrollingMs to 2)
        for ((ms, kind) in parts) {
            val segW = w * ms / total
            if (segW < 1f) continue
            drawPart(canvas, x, x + segW, h, kind)
            x += segW
        }
    }

    private fun drawPart(c: Canvas, l: Float, r: Float, h: Float, kind: Int) {
        if (!p.isBlack) {
            fill.color = when (kind) { 0 -> p.fill; 1 -> p.soft; else -> p.warn }
            c.drawRect(l, 0f, r, h, fill)
            return
        }
        when (kind) {
            0 -> { fill.color = p.text; c.drawRect(l, 0f, r, h, fill) }
            1 -> {
                c.save(); c.clipRect(l, 0f, r, h)
                var x = l - h
                while (x < r) { c.drawLine(x, h, x + h, 0f, hatch); x += 4 * d }
                c.restore()
            }
            else -> {
                hatch.pathEffect = DashPathEffect(floatArrayOf(2 * d, 2 * d), 0f)
                c.drawLine(l, h / 2, r, h / 2, hatch)
                hatch.pathEffect = null
            }
        }
        c.drawLine(r, 0f, r, h, stroke)
    }
}

/** Home's "Today" block and its detail sheet. */
object TimeStats {
    fun homeBlock(host: HomeActivity): View {
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12)) }
        val head = ui.horizontal()
        head.addView(ui.mono("Today", 10f), ui.lp(0, weight = 1f))
        val total = ui.mono("", 10f)
        head.addView(total, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(head)
        val bar = TimeSplitBar(host, p)
        col.addView(bar, ui.lp().apply { topMargin = ui.dp(7) })
        val legend = ui.mono("", 10f, p.text)
        col.addView(legend, ui.lp().apply { topMargin = ui.dp(6) })
        ui.tappable(col, { sheet(host) })
        col.contentDescription = "Today's phone time. Tap for details."
        Focus.day(host, 0) { d ->
            bar.day = d
            legend.text = (if (d.hasUsage) {
                "Read ${Focus.format(d.readingMs)} · Work ${Focus.format(d.workMs)} · Scroll ${Focus.format(d.scrollingMs)}"
            } else {
                "Read ${Focus.format(d.readingMs)} · Tap to compare with scrolling"
            }).uppercase()
            total.text = if (d.hasUsage) "${Focus.format(d.totalMs)} on phone".uppercase() else ""
            bar.visibility = if (d.hasUsage) View.VISIBLE else View.GONE
        }
        return ui.vertical().apply {
            addView(ui.rule(soft = true))
            addView(col)
        }
    }

    fun sheet(host: HomeActivity) {
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(12)) }
        col.addView(ui.mono("Your phone time", 11f).also { ui.margins(it, 18, 16, 18, 8) })
        col.addView(ui.rule())
        if (!Focus.hasUsageAccess(host)) {
            col.addView(ui.text(
                "Allow usage access to compare reading with work and scrolling apps. It stays on your phone; nothing is sent anywhere.",
                14f, p.soft,
            ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 14, 18, 12) })
            col.addView(ui.boxButton("Allow usage access") { openUsageAccess(host) }.also { ui.margins(it, 18, 0, 18, 8) })
        }
        val table = ui.vertical()
        col.addView(table)
        host.showSheet(col)
        // Today, yesterday and the last 7 days, computed in the background.
        val days = arrayOfNulls<Focus.Day>(8)
        var done = 0
        for (i in 0 until 8) Focus.day(host, i) { d ->
            days[i] = d
            if (++done == 8) fill(host, table, days.map { it!! })
        }
    }

    private fun fill(host: HomeActivity, table: LinearLayout, days: List<Focus.Day>) {
        val ui = host.ui
        val p = ui.p
        val week = days.drop(1)
        fun avg(sel: (Focus.Day) -> Long) = week.sumOf(sel) / 7
        val rows = listOf(
            Triple("Reading", Focus.Day::readingMs, true),
            Triple("Work", Focus.Day::workMs, false),
            Triple("Scrolling", Focus.Day::scrollingMs, false),
            Triple("Other", Focus.Day::otherMs, false),
        )
        val has = days[0].hasUsage
        val head = ui.horizontal().apply { setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(6)) }
        head.addView(ui.mono("", 10f), ui.lp(0, weight = 1.3f))
        for (h in listOf("Today", "Yesterday", "7-day avg")) head.addView(ui.mono(h, 10f).apply { gravity = Gravity.END }, ui.lp(0, weight = 1f))
        table.addView(head)
        for ((label, sel, always) in rows) {
            if (!has && !always) continue
            val r = ui.horizontal().apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)) }
            r.addView(ui.text(label, 15f), ui.lp(0, weight = 1.3f))
            for (v in listOf(sel(days[0]), sel(days[1]), avg(sel))) {
                r.addView(ui.mono(Focus.format(v), 12f, p.text).apply { gravity = Gravity.END }, ui.lp(0, weight = 1f))
            }
            table.addView(r)
            table.addView(ui.rule(soft = true))
        }
        val resisted = Focus.resisted(host, 0)
        if (resisted > 0) table.addView(ui.text(
            "You chose your book over a distracting app $resisted ${if (resisted == 1) "time" else "times"} today.",
            14f, p.text,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 14, 18, 4) })
        table.addView(ui.text(
            "Reading counts Book Launcher's reader, time in reading apps like Kindle, and audiobook listening in apps like Audible (screen on or off, once Now listening is allowed). Long-press any app to change whether it counts as work or pauses as distracting.",
            12.5f, p.soft,
        ).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, 18, 12, 18, 4) })
    }

    fun openUsageAccess(context: Context) {
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + context.packageName))
        if (!app.booklauncher.data.PhoneCheck.open(context, direct, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) {
            Toast.makeText(context, "Open Settings → Usage access", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * One notice at a time on Home, most important first: a phone problem, then the one-time
 * explanation of the app pause.
 */
object HomeNotice {
    fun build(host: HomeActivity): View? {
        val ui = host.ui
        val p = ui.p
        val problem = app.booklauncher.data.PhoneCheck.run(host)
            .firstOrNull { it.level == app.booklauncher.data.PhoneCheck.Level.PROBLEM && it.id !in host.laterNotices }
        if (problem != null) {
            return card(host, problem.title, problem.hint, "Fix", {
                if (problem.id == "folders") host.pickFolder() else problem.fix(host)
            }, "Later") { host.laterNotices += problem.id; host.rebuild() }
        }
        val prefs = host.prefs
        if (prefs.pauseEnabled && "pause_intro" !in prefs.dismissedNotices) {
            val names = host.catalog.apps.filter { Focus.isDistracting(prefs, host.catalog, it.component.packageName) }
                .map { prefs.label(it.key, it.label) }.distinct()
            if (names.isNotEmpty()) {
                val list = if (names.size <= 2) names.joinToString(" and ") else "${names.take(2).joinToString(", ")} and ${names.size - 2} more"
                return card(host, "A pause before feeds",
                    "Opening $list from here now waits a few seconds and offers your book instead.",
                    "Change", { host.push(FocusAppsScreen(host)) }, "OK") {
                    prefs.dismissedNotices = prefs.dismissedNotices + "pause_intro"
                }
            }
        }
        return null
    }

    private fun card(host: HomeActivity, title: String, body: String, yes: String, onYes: () -> Unit, no: String, onNo: () -> Unit): View {
        val ui = host.ui
        val p = ui.p
        val col = ui.vertical().apply {
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.box(0, dashed = true)
        }
        col.addView(ui.text(title, 15f, face = Fonts.sansBold))
        col.addView(ui.text(body, 13.5f, p.soft).apply { setLineSpacing(0f, 1.3f) }.also { ui.margins(it, t = 4) })
        val row = ui.horizontal()
        row.addView(ui.mono(yes, 11f, p.text).apply {
            setPadding(0, ui.dp(10), ui.dp(18), ui.dp(2))
            ui.tappable(this, onYes)
        }, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(ui.mono(no, 11f, p.soft).apply {
            setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(2))
            ui.tappable(this, onNo)
        }, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(row)
        return col.also { ui.margins(it, 18, 18, 18, 0) }
    }
}
