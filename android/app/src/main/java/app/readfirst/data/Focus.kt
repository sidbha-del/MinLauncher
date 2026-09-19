package app.readfirst.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Focus: which apps count as distracting or work, time spent reading (our own reader timer),
 * and a day's phone time split into Reading / Work / Scrolling / Other from Android's usage
 * statistics (only with the user's usage access).
 */
object Focus {

    /** Suggested distracting apps: endless feeds. WhatsApp and LinkedIn are left out on purpose. */
    val SUGGESTED_DISTRACTING = setOf(
        "com.instagram.android", "com.instagram.lite", "com.instagram.barcelona",
        "com.facebook.katana", "com.facebook.lite",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.snapchat.android", "com.twitter.android", "com.reddit.frontpage", "com.pinterest",
        "in.mohalla.sharechat", "in.mohalla.video", "com.eterno.shortvideos", "com.moj.app",
        "com.netflix.mediaclient",
    )

    /** A best guess at work apps; the user can change any of it from the app's long-press menu. */
    val SUGGESTED_WORK = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.microsoft.teams", "com.Slack",
        "us.zoom.videomeetings", "com.google.android.apps.meetings", "com.google.android.apps.docs",
        "com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides", "com.google.android.calendar",
        "com.microsoft.office.officehubrow", "com.microsoft.office.word", "com.microsoft.office.excel",
        "notion.id", "com.atlassian.android.jira.core", "com.google.android.keep", "com.todoist",
        "com.microsoft.skydrive", "com.dropbox.android", "com.asana.app", "com.trello",
    )

    // ---- categories ---------------------------------------------------------------------------

    fun distracting(prefs: Prefs, catalog: AppCatalog): Set<String> =
        prefs.distracting ?: installedSuggested(catalog)

    /** Suggested distracting apps that are actually installed. */
    fun installedSuggested(catalog: AppCatalog): Set<String> =
        catalog.apps.map { it.component.packageName }.filter { it in SUGGESTED_DISTRACTING }.toSet()

    fun isDistracting(prefs: Prefs, catalog: AppCatalog, pkg: String) = pkg in distracting(prefs, catalog)

    fun setDistracting(prefs: Prefs, catalog: AppCatalog, pkg: String, on: Boolean) {
        val now = distracting(prefs, catalog)
        prefs.distracting = if (on) now + pkg else now - pkg
    }

    fun isWork(context: Context, prefs: Prefs, pkg: String): Boolean {
        if (pkg in prefs.workRemoved) return false
        if (pkg in prefs.workAdded || pkg in SUGGESTED_WORK) return true
        return runCatching {
            context.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_PRODUCTIVITY
        }.getOrDefault(false)
    }

    fun setWork(context: Context, prefs: Prefs, pkg: String, on: Boolean) {
        if (on) {
            prefs.workRemoved = prefs.workRemoved - pkg
            if (!isWork(context, prefs, pkg)) prefs.workAdded = prefs.workAdded + pkg
        } else {
            prefs.workAdded = prefs.workAdded - pkg
            if (isWork(context, prefs, pkg)) prefs.workRemoved = prefs.workRemoved + pkg
        }
    }

    // ---- reading timer ------------------------------------------------------------------------

    private const val IDLE_CAP_MS = 3 * 60_000L

    /**
     * Counts reading time in our reader: time between page turns, capped so a book left open on
     * the table doesn't count as hours of reading.
     */
    class ReadingTimer(context: Context) {
        private val sp = context.applicationContext.getSharedPreferences("reading_time", Context.MODE_PRIVATE)
        private var last = 0L

        fun start() { last = System.currentTimeMillis() }

        /** Call on every page turn and when the reader leaves the screen. */
        fun tick() {
            if (last == 0L) return
            val now = System.currentTimeMillis()
            val add = (now - last).coerceIn(0, IDLE_CAP_MS)
            last = now
            if (add <= 0) return
            val key = dayKey(now)
            sp.edit().putLong(key, sp.getLong(key, 0) + add).apply()
        }

        fun stop() {
            tick()
            last = 0L
        }
    }

    /** Audiobook playback time (from [ListeningTracker]), added to today's reading. */
    fun addListening(context: Context, ms: Long) {
        if (ms <= 0 || ms > 12 * 3600_000L) return
        val sp = context.getSharedPreferences("reading_time", Context.MODE_PRIVATE)
        val key = "l" + dayKey(System.currentTimeMillis())
        sp.edit().putLong(key, sp.getLong(key, 0) + ms).apply()
    }

    fun listeningMs(context: Context, dayStart: Long): Long =
        context.getSharedPreferences("reading_time", Context.MODE_PRIVATE).getLong("l" + dayKey(dayStart), 0)

    /** The user chose their book (or "Not now") at the pause instead of opening the app. */
    fun recordResisted(context: Context) {
        val sp = context.getSharedPreferences("reading_time", Context.MODE_PRIVATE)
        val key = "r" + dayKey(System.currentTimeMillis())
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    fun resisted(context: Context, daysAgo: Int): Int =
        context.getSharedPreferences("reading_time", Context.MODE_PRIVATE).getInt("r" + dayKey(startOfDay(daysAgo)), 0)

    fun readingMs(context: Context, dayStart: Long): Long =
        context.getSharedPreferences("reading_time", Context.MODE_PRIVATE).getLong(dayKey(dayStart), 0)

    private fun dayKey(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "d%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun startOfDay(daysAgo: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    // ---- usage statistics --------------------------------------------------------------------

    data class Day(val readingMs: Long, val workMs: Long, val scrollingMs: Long, val otherMs: Long, val hasUsage: Boolean) {
        val totalMs get() = readingMs + workMs + scrollingMs + otherMs
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Computes the day [daysAgo] (0 = today) off the main thread; [onReady] runs on the main thread. */
    fun day(context: Context, daysAgo: Int, onReady: (Day) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val d = runCatching { computeDay(app, daysAgo) }.getOrElse {
                val start = startOfDay(daysAgo)
                Day(readingMs(app, start), 0, 0, 0, false)
            }
            main.post { onReady(d) }
        }
    }

    /** Blocking. */
    fun computeDay(context: Context, daysAgo: Int): Day {
        val start = startOfDay(daysAgo)
        val end = if (daysAgo == 0) System.currentTimeMillis() else startOfDay(daysAgo - 1)
        // Our reader's timer plus audiobook playback (which mostly happens with the screen off).
        val ownReading = readingMs(context, start) + listeningMs(context, start)
        if (!hasUsageAccess(context)) return Day(ownReading, 0, 0, 0, false)
        val perApp = foregroundTime(context, start, end)
        val prefs = Prefs.get(context)
        val catalog = AppCatalog.get(context)
        val distracting = distracting(prefs, catalog)
        // Ebook apps count by screen time (Play Books, Kobo and Libby also play audio: both count).
        // Audiobook-only apps like Audible count by playback time instead, since that's mostly screen-off.
        val readingApps = ReaderApps.PACKAGES.toSet()
        var reading = ownReading
        var work = 0L
        var scrolling = 0L
        var other = 0L
        for ((pkg, ms) in perApp) {
            when {
                pkg == context.packageName -> {} // our reader is counted by our own timer; Home isn't phone use to split
                pkg in readingApps -> reading += ms
                NowPlaying.isAudiobookApp(context, pkg) -> {} // counted by playback time
                pkg in distracting -> scrolling += ms
                isWork(context, prefs, pkg) -> work += ms
                isSystemUi(pkg) -> {}
                else -> other += ms
            }
        }
        return Day(reading, work, scrolling, other, true)
    }

    private fun isSystemUi(pkg: String) = pkg == "com.android.systemui" || pkg.contains("launcher") || pkg == "android"

    /** Foreground time per package between [start] and [end], from resume/pause events. */
    private fun foregroundTime(context: Context, start: Long, end: Long): Map<String, Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val events = usm.queryEvents(start, end)
        val openSince = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> openSince[pkg] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val s = openSince.remove(pkg) ?: continue
                    total[pkg] = (total[pkg] ?: 0) + (e.timeStamp - s).coerceAtLeast(0)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Screen off ends whatever was open.
                    for ((p, s) in openSince) total[p] = (total[p] ?: 0) + (e.timeStamp - s).coerceAtLeast(0)
                    openSince.clear()
                }
            }
        }
        for ((p, s) in openSince) total[p] = (total[p] ?: 0) + (end - s).coerceAtLeast(0)
        return total
    }

    fun format(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "0m"
            m < 60 -> "${m}m"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
}
