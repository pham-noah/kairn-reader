package app.kairn.ui

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
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
 * Reading aloud is supposed to keep going with the screen off. The voice, the page turn and the sleep timer
 * live in the reader process, and the service exists to stop the system reclaiming it - but the page turn
 * itself is a WebView navigation, and a stopped activity is exactly where a WebView is expected to go quiet.
 *
 * This drives the same call the TTS callback drives (followVoice) while the activity is STOPPED, and checks
 * the reader actually moved. The saved position is the assertion: the reader writes it after a page turns.
 */
@RunWith(AndroidJUnit4::class)
class TtsFollowBehindLockTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: KairnApp get() = inst.targetContext.applicationContext as KairnApp

    private fun publish(asset: String, id: String): Book {
        val dest = File(app.store.copiesDir, "$id.epub")
        inst.context.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        val book = Book(id, id, "", android.net.Uri.fromFile(dest).toString())
        app.store.put(book)
        app.store.flush()
        return book
    }

    private fun open(id: String): ActivityScenario<ReaderActivity> = ActivityScenario.launch(
        Intent(inst.targetContext, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun awaitOpen(id: String): Float {
        val end = SystemClock.uptimeMillis() + 40_000
        while (SystemClock.uptimeMillis() < end) {
            app.store.get(id)?.let { if (it.locator != null) return it.progress }
            SystemClock.sleep(300)
        }
        throw AssertionError("the reader never opened $id")
    }

    /** Turns one page through the voice path and waits for the saved position to move. */
    private fun turnAndAwait(id: String, before: Float, ratio: Float, scenario: ActivityScenario<ReaderActivity>): Float {
        scenario.onActivity { it.followVoiceForTest(ratio) }
        val end = SystemClock.uptimeMillis() + 25_000
        var now = before
        while (SystemClock.uptimeMillis() < end) {
            now = app.store.get(id)?.progress ?: before
            if (now > before) return now
            SystemClock.sleep(250)
        }
        return now
    }

    @Test fun thePageTurnsWhileResumedAndWhileStopped() {
        val id = "tts-lock-test"
        publish("paging-ltr.epub", id)
        val scenario = open(id)
        try {
            val opened = awaitOpen(id)

            // Control: the same call, activity resumed. If this does not move, the hook is what is broken.
            val resumed = turnAndAwait(id, opened, 0.5f, scenario)
            assertTrue("no page turn even while resumed (progress $opened)", resumed > opened)

            // Stopped, like a locked screen: onPause and onStop have run, the process is alive.
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(Lifecycle.State.CREATED, scenario.state)

            val stopped = turnAndAwait(id, resumed, 0.95f, scenario)
            assertTrue("the reader did not turn the page while stopped (progress stayed $resumed)", stopped > resumed)
        } finally {
            scenario.close()
            app.store.remove(id)
        }
    }
}
