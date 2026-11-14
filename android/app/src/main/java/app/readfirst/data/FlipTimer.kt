package app.readfirst.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * A reading (or leisure) countdown that belongs to a companion app, TorchLevels: it keeps the
 * time, watches for the phone being placed face-down, blinks the torch and plays the sound at the
 * end. ReadFirst is only the screen in front of it, and talks to it through three calls on its
 * content provider: `start`, `cancel` and `state`.
 *
 * TorchLevels is not on most phones. Everything here answers "not available" or "nothing
 * running" instead of throwing when it is missing, out of date or refuses the call, and the
 * timer's entry points hide themselves on [isAvailable].
 */
object FlipTimer {
    const val PACKAGE = "dev.m42.torchlevels"
    const val AUTHORITY = "dev.m42.torchlevels.timer"
    private val URI: Uri = Uri.parse("content://$AUTHORITY")

    enum class Phase { IDLE, ARMED, RUNNING, RINGING }

    /** [endsAt] is epoch ms and only meaningful while [Phase.RUNNING]; [label] is what we sent. */
    data class State(val phase: Phase, val endsAt: Long, val label: String) {
        val active: Boolean get() = phase != Phase.IDLE

        /** Never negative; 0 unless running. */
        fun remainingMs(now: Long = System.currentTimeMillis()): Long =
            if (phase == Phase.RUNNING) (endsAt - now).coerceAtLeast(0) else 0

        /** The cramped name for Home's status row: READ, LEISURE, or whatever else was sent. */
        val shortLabel: String
            get() = when {
                label.equals(READING, ignoreCase = true) -> "READ"
                label.isBlank() -> "TIMER"
                else -> label.uppercase().take(8)
            }

        /** One line for Home's status row: TIMER, READ 18:42, READ · FACE-DOWN, READ · TIME'S UP. */
        fun line(now: Long = System.currentTimeMillis()): String = when (phase) {
            Phase.IDLE -> "TIMER"
            Phase.ARMED -> "$shortLabel · FACE-DOWN"
            Phase.RUNNING -> "$shortLabel ${clock(remainingMs(now))}"
            Phase.RINGING -> "$shortLabel · TIME'S UP"
        }
    }

    const val READING = "Reading"
    const val LEISURE = "Leisure"
    val IDLE = State(Phase.IDLE, 0L, "")

    /** The last state heard from TorchLevels, for drawing straight away; [refresh] renews it. */
    @Volatile
    var cached: State = IDLE
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var refreshing = false
    private val waiting = ArrayList<(State) -> Unit>()

    /**
     * Whether TorchLevels is installed and offers the timer. Also checks who owns the provider:
     * with TorchLevels absent any app could claim its authority, and that app should not be sent
     * anything or believed.
     */
    fun isAvailable(context: Context): Boolean = runCatching {
        context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName == PACKAGE
    }.getOrDefault(false)

    /**
     * Starts a [minutes]-long countdown named [label]. [onFlip] true arms it instead: it starts
     * when the phone is next placed face-down. Blocking; see [startAsync].
     */
    fun start(context: Context, minutes: Int, label: String, onFlip: Boolean): Boolean {
        val extras = Bundle().apply {
            putInt("minutes", minutes)
            putString("label", label)
            putBoolean("onFlip", onFlip)
        }
        return call(context, "start", extras)?.getBoolean("ok", false) == true
    }

    fun cancel(context: Context): Boolean = call(context, "cancel", null)?.getBoolean("ok", false) == true

    /** What the timer is doing now, or null if TorchLevels couldn't be asked. Blocking. */
    fun state(context: Context): State? {
        val b = call(context, "state", null) ?: return null
        val phase = when (b.getString("state")) {
            "armed" -> Phase.ARMED
            "running" -> Phase.RUNNING
            "ringing" -> Phase.RINGING
            else -> Phase.IDLE
        }
        return State(phase, b.getLong("endsAt", 0L), b.getString("label").orEmpty())
    }

    /**
     * Asks TorchLevels again, off the main thread (the call crosses into another app and may
     * have to start it), then tells [onState] on the main thread. Calls made while one is
     * already under way share its answer. A failed call counts as "nothing running".
     */
    fun refresh(context: Context, onState: ((State) -> Unit)? = null) {
        val app = context.applicationContext
        synchronized(waiting) {
            if (onState != null) waiting += onState
            if (refreshing) return
            refreshing = true
        }
        worker.execute {
            val state = (if (isAvailable(app)) state(app) else null) ?: IDLE
            cached = state
            val listeners = synchronized(waiting) {
                refreshing = false
                waiting.toList().also { waiting.clear() }
            }
            main.post { listeners.forEach { it(state) } }
        }
    }

    /** [start] off the main thread; [onDone] gets whether it was accepted, and the state after. */
    fun startAsync(context: Context, minutes: Int, label: String, onFlip: Boolean, onDone: (Boolean, State) -> Unit) =
        act(context, { start(it, minutes, label, onFlip) }, onDone)

    fun cancelAsync(context: Context, onDone: (Boolean, State) -> Unit) = act(context, { cancel(it) }, onDone)

    private fun act(context: Context, action: (Context) -> Boolean, onDone: (Boolean, State) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val available = isAvailable(app)
            val ok = available && action(app)
            val state = (if (available) state(app) else null) ?: IDLE
            cached = state
            main.post { onDone(ok, state) }
        }
    }

    /** `mm:ss`, or `h:mm:ss` past the hour. */
    fun clock(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun call(context: Context, method: String, extras: Bundle?): Bundle? =
        runCatching { context.contentResolver.call(URI, method, null, extras) }.getOrNull()
}
