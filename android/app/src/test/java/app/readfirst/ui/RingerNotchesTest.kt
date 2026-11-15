package app.readfirst.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RingerNotchesTest {
    @Test fun notchCount() {
        assertEquals(3, RingerNotches.count(true))
        assertEquals(2, RingerNotches.count(false))
    }

    @Test fun threeNotchMapping() {
        for (n in 0..2) assertEquals(n, RingerNotches.modeIndex(n, true))
        for (m in 0..2) assertEquals(m, RingerNotches.notchFor(m, true))
    }

    @Test fun twoNotchMappingSkipsVibrate() {
        assertEquals(RingerNotches.RING, RingerNotches.modeIndex(0, false))
        assertEquals(RingerNotches.SILENT, RingerNotches.modeIndex(1, false))
        assertEquals(0, RingerNotches.notchFor(RingerNotches.RING, false))
        assertEquals(1, RingerNotches.notchFor(RingerNotches.SILENT, false))
        assertEquals(1, RingerNotches.notchFor(RingerNotches.VIBRATE, false))
    }

    @Test fun nearestNotchFromY() {
        val h = 56f
        assertEquals(0, RingerNotches.nearest(-10f, h, 3))
        assertEquals(0, RingerNotches.nearest(55f, h, 3))
        assertEquals(1, RingerNotches.nearest(56f, h, 3))
        assertEquals(2, RingerNotches.nearest(400f, h, 3))
        assertEquals(1, RingerNotches.nearest(100f, h, 2))
    }

    @Test fun knobStaysOnTrack() {
        assertEquals(28f, RingerNotches.clampKnob(-50f, 56f, 3), 0.001f)
        assertEquals(140f, RingerNotches.clampKnob(500f, 56f, 3), 0.001f)
        assertEquals(70f, RingerNotches.clampKnob(70f, 56f, 3), 0.001f)
    }
}
