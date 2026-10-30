package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the scheduled backup makes, without SAF in the way: whether it is due, and which of our own
 * zips are past the keep limit. The folder itself is DocumentsContract's business; this is the part that can be
 * wrong in a way the reader notices.
 */
class AutoBackupLogicTest {

    private val day = 86_400_000L

    @Test fun neverBackedUpIsDue() {
        assertTrue(autoBackupIsDue(lastAutoBackup = 0L, now = 1_000L, days = 7))
    }

    @Test fun insideTheIntervalItWaits() {
        assertFalse(autoBackupIsDue(lastAutoBackup = 5_000_000L, now = 5_000_000L + 3 * day, days = 7))
    }

    @Test fun atTheIntervalItRuns() {
        assertTrue(autoBackupIsDue(lastAutoBackup = 5_000_000L, now = 5_000_000L + 7 * day, days = 7))
    }

    @Test fun zeroOrNegativeDaysStillMeansADay() {
        assertFalse(autoBackupIsDue(lastAutoBackup = 5_000_000L, now = 5_000_000L + day / 2, days = 0))
    }

    @Test fun pruningKeepsTheNewestAndIgnoresForeignFiles() {
        val names = listOf(
            "kairn-auto-20260101-0900.zip",
            "kairn-auto-20260105-0900.zip",
            "kairn-auto-20260103-0900.zip",
            "someone-elses-file.zip",
            "notes.txt",
        )

        val stale = staleBackups(names, keep = 2)

        assertEquals(listOf("kairn-auto-20260101-0900.zip"), stale)
    }

    @Test fun pruningNeverDeletesTheLastOne() {
        val names = listOf("kairn-auto-20260101-0900.zip")
        assertTrue(staleBackups(names, keep = 0).isEmpty())
        assertTrue(staleBackups(names, keep = 1).isEmpty())
    }
}
