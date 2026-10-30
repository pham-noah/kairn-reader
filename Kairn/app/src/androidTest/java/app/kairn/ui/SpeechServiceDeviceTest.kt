package app.kairn.ui

import android.app.ActivityManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.KairnApp
import app.kairn.data.Book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reading aloud survives the screen going off.
 *
 * The voice belongs to the process: the engine, the publication, the page that follows it and the sleep timer
 * all live in the Activity's process, so the thing that ends a background reading is Android reclaiming that
 * process. [SpeechService] is the media-playback foreground service that prevents it, and the reader starts it
 * on the way out instead of stopping the voice.
 *
 * What is asserted here is the handoff, not the audio: leaving the reader while it reads leaves the service
 * running, and stopping the voice takes the service with it. Sound itself needs a speech engine and ears; this
 * is the part that used to be a `tts.stop()` in onPause and would regress silently.
 */
@RunWith(AndroidJUnit4::class)
class SpeechServiceDeviceTest {

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

    private fun serviceRunning(): Boolean {
        val am = inst.targetContext.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return am.getRunningServices(200).any { it.service.className == SpeechService::class.java.name }
    }

    /** The service is started asynchronously; give the system a moment before calling it missing. */
    private fun awaitRunning(want: Boolean, timeoutMs: Long = 5_000): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (serviceRunning() == want) return true
            Thread.sleep(100)
        }
        return serviceRunning() == want
    }

    @Test
    fun leavingTheReaderWhileItReadsKeepsTheServiceAlive() {
        val book = publish("paging-ltr.epub", "tts-bg")
        ActivityScenario.launch<ReaderActivity>(
            Intent(inst.targetContext, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, book.id)
        ).use { scenario ->
            Thread.sleep(2_000)                       // let the publication open
            scenario.onActivity { it.toggleTts() }    // ttsOn flips immediately, before the engine speaks
            Thread.sleep(1_000)
            assertFalse("foreground service runs only while reading", serviceRunning())

            // CREATED is the background: the Activity's onPause runs, which is where the voice used to stop.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertTrue("leaving the reader must keep the voice alive", awaitRunning(true))

            scenario.onActivity { it.stopTts() }
            assertTrue("stopping the voice stops the service", awaitRunning(false))
        }
    }
}
