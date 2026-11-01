package app.kairn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clock is injected, so these run on the JVM. What is pinned here is the call order the reader relies
 * on: ask, wait, mark after the write. Asking must not move the clock by itself.
 */
class ReaderProgressTest {

    private var now = 1_000_000L
    private val progress = ReaderProgress { now }

    @Test fun nothingSavedYetWritesImmediately() {
        assertEquals(0L, progress.delayFor())
    }

    @Test fun justAfterASaveWaitsForTheDebounce() {
        progress.markSaved()
        assertEquals(ReaderLogic.SAVE_DEBOUNCE_MS, progress.delayFor())
    }

    @Test fun onceTheIntervalHasPassedItWritesImmediately() {
        progress.markSaved()
        now += ReaderLogic.SAVE_INTERVAL_MS
        assertEquals(0L, progress.delayFor())
    }

    @Test fun oneMillisecondShortOfTheIntervalStillDebounces() {
        progress.markSaved()
        now += ReaderLogic.SAVE_INTERVAL_MS - 1
        assertEquals(ReaderLogic.SAVE_DEBOUNCE_MS, progress.delayFor())
    }

    @Test fun askingDoesNotMoveTheClock() {
        progress.markSaved()
        val first = progress.delayFor()
        now += 7_000
        val second = progress.delayFor()
        assertEquals(ReaderLogic.SAVE_DEBOUNCE_MS, first)
        // Seven seconds on with no write in between: still measured from the mark, so this is due now.
        assertEquals(0L, second)
    }

    @Test fun aCancelledDelayDoesNotCountAsASave() {
        progress.markSaved()
        progress.delayFor()          // caller scheduled a write and its delay was cancelled
        assertEquals(ReaderLogic.SAVE_DEBOUNCE_MS, progress.delayFor())
    }
}
