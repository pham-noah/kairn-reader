package app.kairn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the device sweep is allowed to run.
 *
 * It walks MediaStore and external storage, so it is throttled to a day. The exception is a missing book: after
 * a restore onto a fresh install the records are back and their files are not, and the sweep is the thing that
 * re-attaches them. Throttling that away leaves the reader staring at "File missing" with no reason to scan.
 */
class SweepDueTest {

    private val day = 86_400_000L

    @Test fun aFirstRunSweeps() {
        assertTrue(sweepIsDue(force = false, lastSweep = 0L, anyMissing = false, now = 1_000L))
    }

    @Test fun aDayHasNotPassedSoItWaits() {
        assertFalse(sweepIsDue(force = false, lastSweep = 5_000_000L, anyMissing = false, now = 5_000_000L + day / 2))
    }

    @Test fun aDayLaterItSweeps() {
        assertTrue(sweepIsDue(force = false, lastSweep = 5_000_000L, anyMissing = false, now = 5_000_000L + day + 1))
    }

    @Test fun aMissingBookDoesNotSweepAgainWithinTheHour() {
        // The sweep walks every storage provider; a dead book must not turn every launch into that walk.
        assertFalse(sweepIsDue(force = false, lastSweep = 5_000_000L, anyMissing = true, now = 5_000_000L + 60_000L))
    }

    @Test fun aMissingBookSweepsAgainAfterAnHour() {
        assertTrue(sweepIsDue(force = false, lastSweep = 5_000_000L, anyMissing = true, now = 5_000_000L + 3_600_001L))
    }

    @Test fun aHealthyLibraryWaitsTheWholeDayEvenIfASweepIsOld() {
        assertFalse(sweepIsDue(force = false, lastSweep = 5_000_000L, anyMissing = false, now = 5_000_000L + 3_600_001L))
    }

    @Test fun aForcedScanIgnoresEverything() {
        assertTrue(sweepIsDue(force = true, lastSweep = System.currentTimeMillis(), anyMissing = false, now = System.currentTimeMillis()))
    }
}
