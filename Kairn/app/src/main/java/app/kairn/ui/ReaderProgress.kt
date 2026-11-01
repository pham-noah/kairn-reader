package app.kairn.ui

import android.os.SystemClock

/**
 * The bookkeeping behind saving the reading place: how long to wait before writing, and when the last
 * write happened. The decision itself is [ReaderLogic.saveDelay], so it is tested without a device.
 *
 * The order of the calls matters and is the caller's: ask [delayFor], wait, then [markSaved] as the write
 * happens. Marking on schedule instead of on write would let a page turn that was cancelled mid-delay
 * reset the clock, and a run of fast turns would then never save.
 */
internal class ReaderProgress(private val now: () -> Long = { SystemClock.elapsedRealtime() }) {

    private var lastSaved = 0L

    fun delayFor(): Long = ReaderLogic.saveDelay(now(), lastSaved)

    fun markSaved() {
        lastSaved = now()
    }
}
