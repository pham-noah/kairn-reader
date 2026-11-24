package app.kairn

import app.kairn.data.AUTO_SCHEDULE
import app.kairn.data.AUTO_SYSTEM
import app.kairn.data.AppSettings
import app.kairn.data.formatMinutes
import app.kairn.data.isNightAt
import app.kairn.data.minutesOfDay
import app.kairn.data.nightFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * The night window is the only part of a theme schedule that can be wrong without looking wrong: a boundary that
 * runs the wrong way is dark all day, and nobody notices until they open a book at noon.
 */
class NightClockTest {

    @Test fun a_window_across_midnight_wraps() {
        // 21:00 to 08:00 - the normal case, and the one a plain `in from..to` gets backwards.
        assertTrue("21:00 is the start", isNightAt(minutesOfDay(21, 0), minutesOfDay(21, 0), minutesOfDay(8, 0)))
        assertTrue("23:30 is inside", isNightAt(minutesOfDay(23, 30), minutesOfDay(21, 0), minutesOfDay(8, 0)))
        assertTrue("02:00 is inside", isNightAt(minutesOfDay(2, 0), minutesOfDay(21, 0), minutesOfDay(8, 0)))
        assertFalse("08:00 is the end, exclusive", isNightAt(minutesOfDay(8, 0), minutesOfDay(21, 0), minutesOfDay(8, 0)))
        assertFalse("noon is not", isNightAt(minutesOfDay(12, 0), minutesOfDay(21, 0), minutesOfDay(8, 0)))
        assertFalse("20:59 is not", isNightAt(minutesOfDay(20, 59), minutesOfDay(21, 0), minutesOfDay(8, 0)))
    }

    @Test fun a_daytime_window_does_not_wrap() {
        // Nobody sets this, but it must not behave like its mirror image.
        assertTrue(isNightAt(minutesOfDay(13, 0), minutesOfDay(12, 0), minutesOfDay(14, 0)))
        assertFalse(isNightAt(minutesOfDay(2, 0), minutesOfDay(12, 0), minutesOfDay(14, 0)))
    }

    @Test fun an_empty_window_reads_as_always_dark() {
        // Same start and end would be either never or always; "never dark" is not a thing anybody asks for.
        assertTrue(isNightAt(minutesOfDay(0, 0), minutesOfDay(22, 0), minutesOfDay(22, 0)))
        assertTrue(isNightAt(minutesOfDay(22, 0), minutesOfDay(22, 0), minutesOfDay(22, 0)))
    }

    @Test fun times_round_trip_through_the_label() {
        assertEquals("00:00", formatMinutes(minutesOfDay(0, 0)))
        assertEquals("09:05", formatMinutes(minutesOfDay(9, 5)))
        assertEquals("23:59", formatMinutes(minutesOfDay(23, 59)))
        // A stored minute past a day (a hand-edited file) must not print "25:00".
        assertEquals("00:30", formatMinutes(24 * 60 + 30))
        // And the hour itself is clamped, so 24:30 from a settings file is 23:30, not a crash.
        assertEquals(minutesOfDay(23, 30), minutesOfDay(24, 30))
    }

    @Test fun the_switch_decides_which_clock_is_asked() {
        val night = LocalTime.of(22, 0)
        val day = LocalTime.of(14, 0)

        val off = AppSettings(autoSwitch = false, autoMode = AUTO_SCHEDULE)
        assertTrue("off follows the system, whatever the window says", nightFor(off, systemNight = true, now = day))
        assertFalse(nightFor(off, systemNight = false, now = night))

        val system = AppSettings(autoSwitch = true, autoMode = AUTO_SYSTEM)
        assertTrue("system mode follows the phone", nightFor(system, systemNight = true, now = day))
        assertFalse(nightFor(system, systemNight = false, now = night))

        val scheduled = AppSettings(autoSwitch = true, autoMode = AUTO_SCHEDULE, nightFrom = minutesOfDay(21, 0), nightTo = minutesOfDay(8, 0))
        assertTrue("a schedule ignores the phone", nightFor(scheduled, systemNight = false, now = night))
        assertFalse(nightFor(scheduled, systemNight = true, now = day))
    }
}
