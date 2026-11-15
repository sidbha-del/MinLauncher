package app.readfirst.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerDigitsTest {
    @Test fun minutesAndSeconds() {
        assertEquals("18:42", TimerDigits.format(18 * 60_000L + 42_000L, false))
        assertEquals("00:01", TimerDigits.format(1, false))
        assertEquals("00:00", TimerDigits.format(0, false))
        assertEquals("59:59", TimerDigits.format(59 * 60_000L + 59_000L, false))
    }

    @Test fun hoursAndMinutes() {
        assertEquals("1:00", TimerDigits.format(3_600_000L, false))
        assertEquals("2:05", TimerDigits.format(2 * 3_600_000L + 5 * 60_000L + 10_000L, false))
    }

    @Test fun blackInkShowsWholeMinutesRoundedUp() {
        assertEquals("18m", TimerDigits.format(18 * 60_000L, true))
        assertEquals("18m", TimerDigits.format(17 * 60_000L + 30_000L, true))
        assertEquals("0m", TimerDigits.format(0, true))
    }

    @Test fun nextChange() {
        assertEquals(1020L, TimerDigits.nextChangeMs(5_000L, false))
        assertEquals(520L, TimerDigits.nextChangeMs(5_500L, false))
        assertEquals(60_020L, TimerDigits.nextChangeMs(120_000L, true))
    }
}
