package app.kairn.ui

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.KairnApp
import app.kairn.data.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The reader's tap zones, on a device: the edge that pages FORWARD depends on the book's reading direction,
 * and the middle toggles the bars. This is the logic that the scroll-mode swipe fix sits next to, so it gets
 * a test of its own.
 *
 * The assertion uses the saved position, not the screen: the reader writes progress to the book record on a
 * debounced save, so polling that value is an observable proof that a page actually turned.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTapZoneTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: KairnApp get() = inst.targetContext.applicationContext as KairnApp
    private val width: Int get() = inst.targetContext.resources.displayMetrics.widthPixels
    private val height: Int get() = inst.targetContext.resources.displayMetrics.heightPixels

    /** Copies a fixture out of the test APK and registers it as a book, as an import would. */
    private fun publish(asset: String, id: String): Book {
        val dest = File(app.store.copiesDir, "$id.epub")
        inst.context.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        // Uri.fromFile, not java.net.URI: URI(scheme, "", path, null) prints "file:/path" (one slash) and
        // Readium's AbsoluteUrl will not parse it.
        val book = Book(id, id, "", android.net.Uri.fromFile(dest).toString())
        app.store.put(book)
        app.store.flush()
        return book
    }

    private fun tap(x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0))
        inst.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0))
    }

    private fun progress(id: String): Float? = app.store.get(id)?.progress

    private fun open(id: String): ActivityScenario<ReaderActivity> {
        val i = Intent(inst.targetContext, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return ActivityScenario.launch(i)
    }

    /**
     * Waits until the reader has saved a position for the book. The LOCATOR, not progress: at the start of a
     * book progress is legitimately 0.0, so waiting for "> 0" waits for ever.
     */
    private fun awaitOpen(id: String): Float {
        val end = SystemClock.uptimeMillis() + 40_000
        while (SystemClock.uptimeMillis() < end) {
            val b = app.store.get(id)
            if (b?.locator != null) return b.progress
            SystemClock.sleep(300)
        }
        throw AssertionError("the reader never saved a position for $id")
    }

    @Test fun in_a_left_to_right_book_the_right_edge_pages_forward() {
        val id = "tap-ltr"
        publish("paging-ltr.epub", id)
        open(id).use {
            val before = awaitOpen(id)
            tap((width * 0.9f).toInt(), height / 2)
            SystemClock.sleep(3_000)
            val after = progress(id) ?: before
            assertTrue("right edge did not move forward: $before -> $after", after > before)
        }
    }

    @Test fun in_a_right_to_left_book_the_left_edge_pages_forward() {
        val id = "tap-rtl"
        publish("paging-rtl.epub", id)
        open(id).use {
            val before = awaitOpen(id)
            tap((width * 0.1f).toInt(), height / 2)
            SystemClock.sleep(3_000)
            val after = progress(id) ?: before
            assertTrue("left edge did not move forward in an RTL book: $before -> $after", after > before)
        }
    }
}
