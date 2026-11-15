package app.readfirst.ui

/** What the round timer button prints while a countdown runs, kept apart from the view for tests. */
object TimerDigits {
    /**
     * `mm:ss` under an hour, `h:mm` from an hour on; in Black ink (which redraws once a minute, so
     * seconds would only ever be stale) whole minutes, `18m`. Never more than five characters.
     */
    fun format(remainingMs: Long, black: Boolean): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0) + 999) / 1000
        val minutes = totalSeconds / 60
        return when {
            black -> "${if (totalSeconds % 60 != 0L) minutes + 1 else minutes}m"
            totalSeconds >= 3600 -> "%d:%02d".format(totalSeconds / 3600, totalSeconds % 3600 / 60)
            else -> "%02d:%02d".format(minutes, totalSeconds % 60)
        }
    }

    /** Milliseconds until the digits next change: a second away in Colour ink, a minute in Black. */
    fun nextChangeMs(remainingMs: Long, black: Boolean): Long {
        val unit = if (black) 60_000L else 1_000L
        // The digits round up to the next unit, so they change just after a unit boundary.
        val r = remainingMs.coerceAtLeast(0) % unit
        return (if (r == 0L) unit else r) + 20
    }
}
