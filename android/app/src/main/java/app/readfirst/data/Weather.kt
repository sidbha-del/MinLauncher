package app.readfirst.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * One line of weather, resolved from the request's own IP address — no location permission, no
 * API key, no account, using the same plain-HTTPS [Net] fetch as everything else in the app.
 * City-level accuracy, which is the right trade for a status-bar decoration.
 */
object Weather {
    data class State(val line: String)

    @Volatile private var cached: State? = null
    @Volatile private var lastFetch = 0L
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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

    /** The same provider's own human-readable forecast page, same IP-based location. */
    const val WEB_URL = "https://wttr.in/"
}
