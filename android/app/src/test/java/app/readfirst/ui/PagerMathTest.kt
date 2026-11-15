package app.readfirst.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerMathTest {
    private val w = 1000f
    private val density = 2f

    @Test fun slowShortDragStaysOnPage() {
        assertEquals(1, PagerMath.targetPage(1, -100f, -50f, w, density, 3))
    }

    @Test fun dragPastTwentyPercentTurnsPage() {
        assertEquals(2, PagerMath.targetPage(1, -250f, -10f, w, density, 3))
        assertEquals(0, PagerMath.targetPage(1, 250f, 10f, w, density, 3))
    }

    @Test fun flingTurnsPageEvenOnShortDrag() {
        // 400dp/s at density 2 is 800px/s.
        assertEquals(2, PagerMath.targetPage(1, -30f, -900f, w, density, 3))
        assertEquals(1, PagerMath.targetPage(1, -30f, -700f, w, density, 3))
    }

    @Test fun neverMoreThanOnePageAndNeverOutOfRange() {
        assertEquals(2, PagerMath.targetPage(2, -900f, -5000f, w, density, 3))
        assertEquals(0, PagerMath.targetPage(0, 900f, 5000f, w, density, 3))
        assertEquals(2, PagerMath.targetPage(1, -2900f, -9000f, w, density, 3))
    }

    @Test fun settleDurationByDistance() {
        assertEquals(120, PagerMath.settleDurationMs(0f, w, 0f))
        assertEquals(220, PagerMath.settleDurationMs(500f, w, 0f))
        assertEquals(320, PagerMath.settleDurationMs(1000f, w, 0f))
        assertEquals(320, PagerMath.settleDurationMs(-2000f, w, 0f))
    }

    @Test fun flingShortensSettleWithinBounds() {
        // 400px left at 4000px/s: 100ms by speed, 200ms by distance.
        assertEquals(100, PagerMath.settleDurationMs(400f, w, 4000f))
        // A slow fling never lengthens past the distance rule.
        assertEquals(220, PagerMath.settleDurationMs(500f, w, 800f))
        // Very fast and very near never drops below 100ms.
        assertEquals(100, PagerMath.settleDurationMs(10f, w, 8000f))
    }

    @Test fun nearestPageForDots() {
        assertEquals(0, PagerMath.nearestPage(0.49f, 3))
        assertEquals(1, PagerMath.nearestPage(0.5f, 3))
        assertEquals(2, PagerMath.nearestPage(5f, 3))
        assertEquals(0, PagerMath.nearestPage(-1f, 3))
    }
}
