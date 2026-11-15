package app.readfirst.ui

/**
 * The ringer slider's geometry and mapping, on plain numbers so it can be tested. Notches run top
 * to bottom: ring, vibrate, silent; a phone with no vibration motor has two, ring and silent.
 * Modes are named by their index in `Ringer.Mode.entries` (0 ring, 1 vibrate, 2 silent).
 */
object RingerNotches {
    const val RING = 0
    const val VIBRATE = 1
    const val SILENT = 2

    fun count(hasVibrator: Boolean): Int = if (hasVibrator) 3 else 2

    /** Which mode (by index) the notch at [notch] stands for. */
    fun modeIndex(notch: Int, hasVibrator: Boolean): Int = when {
        notch <= 0 -> RING
        hasVibrator && notch == 1 -> VIBRATE
        else -> SILENT
    }

    /** The notch showing [modeIndex]; vibrate on a phone without a motor is, to Android, silent. */
    fun notchFor(modeIndex: Int, hasVibrator: Boolean): Int = when (modeIndex) {
        RING -> 0
        VIBRATE -> 1 // the middle notch with a motor, the silent one (also notch 1) without
        else -> if (hasVibrator) 2 else 1
    }

    /** The notch nearest to [y] (measured from the top of the track area, each notch [notchPx] tall). */
    fun nearest(y: Float, notchPx: Float, count: Int): Int {
        if (notchPx <= 0f || count <= 0) return 0
        return (y / notchPx).toInt().coerceIn(0, count - 1)
    }

    /** Centre of [notch] from the top of the view. */
    fun centerY(notch: Int, notchPx: Float): Float = (notch + 0.5f) * notchPx

    /** A finger position clamped so the knob stays on the track, between the first and last centre. */
    fun clampKnob(y: Float, notchPx: Float, count: Int): Float =
        y.coerceIn(centerY(0, notchPx), centerY((count - 1).coerceAtLeast(0), notchPx))
}
