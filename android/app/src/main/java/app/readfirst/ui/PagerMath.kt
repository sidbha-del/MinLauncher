package app.readfirst.ui

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure maths of the Library / Home / Apps pager, kept apart from the views so it can be tested. */
object PagerMath {
    /** A release faster than this (dp per second) turns the page whatever the distance. */
    const val FLING_DP_PER_SEC = 400f
    /** A slow release turns the page once dragged this share of the width. */
    const val DRAG_SHARE = 0.20f

    /**
     * The page a release settles on: at most one away from [startPage]. A fast fling decides by its
     * direction; otherwise a drag of [DRAG_SHARE] of the width does. [dxPx] is the finger's travel
     * (negative = leftwards = towards the next page), [vxPxPerSec] its velocity in the same sense.
     */
    fun targetPage(startPage: Int, dxPx: Float, vxPxPerSec: Float, widthPx: Float, density: Float, pageCount: Int): Int {
        val dir = when {
            abs(vxPxPerSec) >= FLING_DP_PER_SEC * density -> if (vxPxPerSec < 0) 1 else -1
            abs(dxPx) >= widthPx * DRAG_SHARE -> if (dxPx < 0) 1 else -1
            else -> 0
        }
        return (startPage + dir).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    /**
     * How long the settle takes: 120 ms plus up to 200 ms by the distance left to cover, and for a
     * fling no longer than that distance takes at the finger's own speed (100 to 320 ms).
     */
    fun settleDurationMs(remainingPx: Float, widthPx: Float, vxPxPerSec: Float): Int {
        val share = if (widthPx <= 0f) 1f else (abs(remainingPx) / widthPx).coerceIn(0f, 1f)
        val byDistance = 120f + 200f * share
        val speed = abs(vxPxPerSec)
        if (speed < 1f) return byDistance.roundToInt()
        val bySpeed = (1000f * abs(remainingPx) / speed).coerceIn(100f, 320f)
        return min(byDistance, bySpeed).roundToInt()
    }

    /** The page nearest to a (fractional) pager position, for the dots. */
    fun nearestPage(progress: Float, pageCount: Int): Int = progress.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}
