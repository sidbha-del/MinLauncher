package app.readfirst.ui

import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.readfirst.HomeActivity
import app.readfirst.data.PhoneCheck
import app.readfirst.data.Weather
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the weather line on Home opens: the place, the temperature now, the next few hours and the
 * next three days, in the app's own ink, with a button on to Google's ten-day forecast for the same
 * place. Opens at once with whatever was last fetched (or a calm "Loading weather…") and redraws
 * itself inside the same sheet when the numbers arrive, so nothing slides in twice.
 */
object WeatherSheet {
    private const val GUTTER = 18

    fun show(host: HomeActivity) {
        val ui = host.ui
        val body = ui.vertical()
        var place = ""

        val content = ui.vertical()
        content.addView(CappedScroll(host, 0.78f).apply { addView(body) })
        val google = ui.boxButton("10-day forecast") {
            val url = Weather.googleForecastUrl(place)
            if (!PhoneCheck.open(host, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
                Toast.makeText(host, "No browser found on this device", Toast.LENGTH_SHORT).show()
            }
        }
        val close = ui.boxButton("Close") { host.dismissSheet() }
        listOf(google, close).forEach { it.minHeight = ui.dp(48) }
        content.addView(ui.margins(google, GUTTER, 16, GUTTER, 0))
        content.addView(ui.margins(close, GUTTER, 10, GUTTER, 16))

        fun message(text: String) {
            body.removeAllViews()
            body.addView(ui.mono("Weather", 11f).also { ui.margins(it, GUTTER, 16, GUTTER, 0) })
            body.addView(ui.text(text, 15f, ui.p.soft).also { ui.margins(it, GUTTER, 16, GUTTER, 0) })
        }

        fun draw(d: Weather.Detail) {
            place = d.place
            body.removeAllViews()
            fill(host, body, d)
        }

        message("Loading weather…")
        host.showSheet(content)

        var immediate = true
        Weather.detail { d ->
            // Asked again by the time it answers: only touch this sheet while it is still the one up.
            if (!immediate && !content.isAttachedToWindow) return@detail
            when {
                d != null -> draw(d)
                !immediate -> message("Weather unavailable. Check your connection.")
            }
        }
        immediate = false
    }

    private fun fill(host: HomeActivity, body: LinearLayout, d: Weather.Detail) {
        val ui = host.ui
        val p = ui.p

        body.addView(ui.mono(d.place.ifBlank { "Weather" }, 11f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }.also { ui.margins(it, GUTTER, 16, GUTTER, 0) })

        // The temperature now, with the sky beside it.
        val now = ui.horizontal().apply { gravity = Gravity.BOTTOM }
        now.addView(ui.serif(d.tempC?.let { "$it°" } ?: "–", 56f, bold = true).apply {
            includeFontPadding = false
            setLineSpacing(0f, 1f)
        })
        if (d.condition.isNotBlank()) {
            now.addView(ui.serif(d.condition, 18f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, ui.lp(0, weight = 1f).apply { leftMargin = ui.dp(14); bottomMargin = ui.dp(6) })
        }
        body.addView(ui.margins(now, GUTTER, 8, GUTTER, 0))

        val facts = listOfNotNull(
            d.feelsC?.let { "Feels $it°" },
            d.humidity?.let { "Humidity $it%" },
            d.windKmph?.let { "Wind $it km/h" + if (d.windDir.isNotBlank()) " ${d.windDir}" else "" },
            d.uv?.let { "UV $it" },
        )
        if (facts.isNotEmpty()) {
            body.addView(ui.text(facts.joinToString(" · "), 13f, p.soft).apply { setLineSpacing(0f, 1.2f) }
                .also { ui.margins(it, GUTTER, 10, GUTTER, 0) })
        }
        if (d.observed.isNotBlank()) {
            body.addView(ui.mono("Updated ${d.observed}", 10f).also { ui.margins(it, GUTTER, 6, GUTTER, 0) })
        }

        if (d.hours.isNotEmpty()) {
            body.addView(ui.rule(soft = true).also { ui.margins(it, GUTTER, 16, GUTTER, 0) })
            val is24 = DateFormat.is24HourFormat(host)
            val row = ui.horizontal()
            for (i in 0 until 6) {
                val h = d.hours.getOrNull(i)
                val cell = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                if (h != null) {
                    cell.addView(ui.mono(hourLabel(h.minutes, is24), 10f))
                    cell.addView(ui.serif(h.tempC?.let { "$it°" } ?: "–", 18f).also { ui.margins(it, t = 4) })
                    cell.addView(ui.text(h.condition, 10f, p.soft).apply {
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }.also { ui.margins(it, t = 2) })
                    if ((h.rainPct ?: 0) > 0) cell.addView(ui.mono("${h.rainPct}%", 9f).also { ui.margins(it, t = 2) })
                }
                row.addView(cell, ui.lp(0, weight = 1f))
            }
            body.addView(ui.margins(row, GUTTER, 14, GUTTER, 14))
        }

        if (d.days.isNotEmpty()) {
            body.addView(ui.rule(soft = true).also { ui.margins(it, GUTTER, if (d.hours.isEmpty()) 16 else 0, GUTTER, 0) })
            d.days.forEachIndexed { i, day ->
                val row = ui.horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ui.dp(48)
                }
                row.addView(ui.mono(dayName(day.date, i), 11f, p.text), LinearLayout.LayoutParams(ui.dp(52), LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(ui.text(day.condition, 13f, p.soft).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, ui.lp(0, weight = 1f).apply { rightMargin = ui.dp(8) })
                row.addView(ui.mono(if ((day.rainPct ?: 0) > 0) "${day.rainPct}%" else "", 10f).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(ui.dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))
                val range = (day.maxC?.let { "$it°" } ?: "–") + " / " + (day.minC?.let { "$it°" } ?: "–")
                row.addView(ui.serif(range, 15f).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(ui.dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
                body.addView(ui.margins(row, GUTTER, 0, GUTTER, 0))
                if (i < d.days.size - 1) body.addView(ui.rule(soft = true).also { ui.margins(it, GUTTER, 0, GUTTER, 0) })
            }
            body.addView(ui.rule(soft = true).also { ui.margins(it, GUTTER, 0, GUTTER, 0) })
        }
    }

    /** "09:00" or "9 AM", whichever the phone is set to. */
    private fun hourLabel(minutes: Int, is24: Boolean): String {
        val h = minutes / 60
        if (is24) return String.format(Locale.US, "%02d:%02d", h, minutes % 60)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12 " + if (h < 12) "AM" else "PM"
    }

    /** "Today" for the first row, otherwise the short weekday ("Mon"). */
    private fun dayName(date: String, index: Int): String {
        if (index == 0) return "Today"
        return runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            .getOrDefault(date)
    }
}
