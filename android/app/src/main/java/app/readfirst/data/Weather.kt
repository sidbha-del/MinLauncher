package app.readfirst.data

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * One line of weather, resolved from the request's own IP address — no location permission, no
 * API key, no account, using the same plain-HTTPS [Net] fetch as everything else in the app.
 * City-level accuracy, which is the right trade for a status-bar decoration. [detail] is the
 * fuller picture behind the weather sheet, from the same provider.
 */
object Weather {
    data class State(val line: String)

    @Volatile private var cached: State? = null
    @Volatile private var lastFetch = 0L
    // Lazy so the pure parsing below can run in plain JVM unit tests, where Looper isn't available.
    private val worker by lazy { Executors.newSingleThreadExecutor() }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Hands back whatever's cached immediately, then refreshes in the background if the cache
     *  is more than 30 minutes old (calling [onUpdate] again on the main thread if it changes). */
    fun current(onUpdate: (State?) -> Unit) {
        onUpdate(cached)
        val now = System.currentTimeMillis()
        if (now - lastFetch < 30 * 60_000L) return
        lastFetch = now
        worker.execute {
            val line = runCatching {
                String(Net.get("https://wttr.in/?format=%t+%C", maxBytes = 4096)).trim()
            }.getOrNull()
            val state = line?.takeIf { it.isNotBlank() && it.length < 40 && !it.contains("Unknown", ignoreCase = true) }?.let { State(it) }
            if (state != null) {
                cached = state
                main.post { onUpdate(state) }
            }
        }
    }

    // ---- the detail sheet ---------------------------------------------------------------------

    /** One forecast point. [minutes] is minutes after local midnight. */
    data class Hour(val minutes: Int, val tempC: Int?, val condition: String, val rainPct: Int?)

    data class Day(val date: String, val maxC: Int?, val minC: Int?, val condition: String, val rainPct: Int?)

    /** Everything the weather sheet shows. Every scalar may be missing (null or blank). */
    data class Detail(
        val place: String,
        val condition: String,
        val tempC: Int?,
        val feelsC: Int?,
        val humidity: Int?,
        val windKmph: Int?,
        val windDir: String,
        val uv: Int?,
        val observed: String,
        val hours: List<Hour>,
        val days: List<Day>,
    )

    private const val CACHE_MS = 30 * 60_000L

    @Volatile private var detailCached: Detail? = null
    @Volatile private var lastDetailFetch = 0L
    private var detailBusy = false
    private val detailListeners = ArrayList<(Detail?) -> Unit>()

    /**
     * Hands back whatever detail is cached at once (null when there is none yet), then refreshes
     * in the background when it is over 30 minutes old and calls [onUpdate] on the main thread:
     * with the new detail, or with null when the fetch failed and nothing at all is cached. A
     * failure with an older copy on hand stays silent, so the sheet keeps showing that.
     */
    fun detail(onUpdate: (Detail?) -> Unit) {
        onUpdate(detailCached)
        val now = System.currentTimeMillis()
        synchronized(detailListeners) {
            val fresh = detailCached != null && now - lastDetailFetch < CACHE_MS
            if (fresh) return
            detailListeners += onUpdate
            if (detailBusy) return
            detailBusy = true
            lastDetailFetch = now
        }
        worker.execute {
            val parsed = runCatching {
                val json = String(Net.get("https://wttr.in/?format=j1", maxBytes = 262144), Charsets.UTF_8)
                val c = java.util.Calendar.getInstance()
                val today = String.format(
                    java.util.Locale.US, "%04d-%02d-%02d",
                    c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                )
                parseDetail(json, c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE), today)
            }.getOrNull()
            val listeners = synchronized(detailListeners) {
                detailBusy = false
                if (parsed != null) detailCached = parsed else lastDetailFetch = 0L // retry on the next open
                val waiting = detailListeners.toList()
                detailListeners.clear()
                // A failure with an older copy on hand needs no word: the sheet already shows it.
                if (parsed == null && detailCached != null) emptyList() else waiting
            }
            if (listeners.isNotEmpty()) main.post { listeners.forEach { it(parsed) } }
        }
    }

    /**
     * Reads wttr.in's `format=j1` answer. [nowMinutesOfDay] and [today] (`yyyy-MM-dd`) are local
     * time, passed in so this stays pure. Every field is optional; null only when the text isn't
     * a usable forecast at all.
     */
    fun parseDetail(json: String, nowMinutesOfDay: Int, today: String): Detail? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // Some wttr.in deployments wrap the answer in "data".
        val data = root.optJSONObject("data") ?: root
        val current = data.optJSONArray("current_condition")?.optJSONObject(0)
        val weather = data.optJSONArray("weather")
        val days = ArrayList<JSONObject>()
        if (weather != null) for (i in 0 until weather.length()) weather.optJSONObject(i)?.let { days += it }
        if (current == null && days.isEmpty()) return null
        days.sortBy { it.optString("date", "") }

        val area = data.optJSONArray("nearest_area")?.optJSONObject(0)
        val place = if (area == null) "" else shortPlace(firstValue(area, "areaName"), firstValue(area, "region"), firstValue(area, "country"))

        // "2026-11-15 10:23 AM" -> "10:23 AM"
        val observed = current?.optString("localObsDateTime", "").orEmpty().trim()
            .let { Regex("^\\d{4}-\\d{2}-\\d{2}\\s+(.+)$").find(it)?.groupValues?.get(1) ?: it }

        // The next six points from now, rolling into tomorrow; days before today are ignored.
        val hours = ArrayList<Hour>()
        for (day in days) {
            val date = day.optString("date", "")
            if (date.isNotEmpty() && date < today) continue
            val hourly = day.optJSONArray("hourly") ?: continue
            val points = ArrayList<Hour>()
            for (j in 0 until hourly.length()) {
                val h = hourly.optJSONObject(j) ?: continue
                val minutes = timeToMinutes(h.optString("time", "")) ?: continue
                if (date == today && minutes < nowMinutesOfDay) continue
                points += Hour(minutes, h.optString("tempC").toIntOrNull(), firstValue(h, "weatherDesc"), h.optString("chanceofrain").toIntOrNull())
            }
            hours += points.sortedBy { it.minutes }
        }

        val threeDays = days.filter { val d = it.optString("date", ""); d.isEmpty() || d >= today }.take(3).map { day ->
            val hourly = day.optJSONArray("hourly")
            var noon: JSONObject? = null
            var first: JSONObject? = null
            var rain: Int? = null
            if (hourly != null) for (j in 0 until hourly.length()) {
                val h = hourly.optJSONObject(j) ?: continue
                if (first == null) first = h
                if (timeToMinutes(h.optString("time", "")) == 12 * 60) noon = h
                val r = h.optString("chanceofrain").toIntOrNull()
                if (r != null && (rain == null || r > rain)) rain = r
            }
            Day(
                day.optString("date", ""),
                day.optString("maxtempC").toIntOrNull(),
                day.optString("mintempC").toIntOrNull(),
                (noon ?: first)?.let { firstValue(it, "weatherDesc") }.orEmpty(),
                rain,
            )
        }

        return Detail(
            place = place,
            condition = current?.let { firstValue(it, "weatherDesc") }.orEmpty(),
            tempC = current?.optString("temp_C")?.toIntOrNull(),
            feelsC = current?.optString("FeelsLikeC")?.toIntOrNull(),
            humidity = current?.optString("humidity")?.toIntOrNull(),
            windKmph = current?.optString("windspeedKmph")?.toIntOrNull(),
            windDir = current?.optString("winddir16Point").orEmpty(),
            uv = current?.optString("uvIndex")?.toIntOrNull(),
            observed = observed,
            hours = hours.take(6),
            days = threeDays,
        )
    }

    /** wttr.in writes 9:00 as "900", midnight as "0", 21:00 as "2100". */
    private fun timeToMinutes(raw: String): Int? {
        val n = raw.trim().toIntOrNull() ?: return null
        val h = n / 100
        val m = n % 100
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    private fun firstValue(obj: JSONObject, key: String): String {
        val arr: JSONArray = obj.optJSONArray(key) ?: return ""
        return arr.optJSONObject(0)?.optString("value", "")?.trim().orEmpty()
    }

    /** "Area, Region": the region is dropped when blank or the same as the area; the country
     *  stands in only when there is nothing else. */
    internal fun shortPlace(area: String, region: String, country: String): String {
        val a = area.trim()
        val r = region.trim()
        return when {
            a.isNotEmpty() && r.isNotEmpty() && !a.equals(r, ignoreCase = true) -> "$a, $r"
            a.isNotEmpty() -> a
            r.isNotEmpty() -> r
            else -> country.trim()
        }
    }

    /** Google's search for a 10-day forecast at [place] (just the query when there's no place). */
    fun googleForecastUrl(place: String): String {
        val query = if (place.isBlank()) "10 day weather forecast" else "10 day weather forecast ${place.trim()}"
        return "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
    }

    /** The same provider's own human-readable forecast page, same IP-based location. */
    const val WEB_URL = "https://wttr.in/"
}
