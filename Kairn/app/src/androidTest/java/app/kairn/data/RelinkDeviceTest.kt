package app.kairn.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.KairnApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A book whose file link died - a picker permit from an older install, or a record restored without its file -
 * must be re-attached to Kairn's own copy by the next scan, without the reader being asked to scan the device.
 * This is the promise that a restore or an update lands you back in your library.
 */
@RunWith(AndroidJUnit4::class)
class RelinkDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: KairnApp get() = inst.targetContext.applicationContext as KairnApp

    @After fun clean() {
        app.store.remove("relink-test")
        File(app.store.copiesDir, "relink-test.epub").delete()
    }

    @Test fun aDeadLinkIsRepointedAtOurOwnCopy() {
        val copy = File(app.store.copiesDir, "relink-test.epub")
        inst.context.assets.open("paging-ltr.epub").use { i -> copy.outputStream().use { o -> i.copyTo(o) } }
        // The record points at a place that does not exist, as a stale grant would.
        val dead = Uri.fromFile(File(inst.targetContext.cacheDir, "relink-test.epub")).toString()
        app.store.put(Book("relink-test", "Relink", "", dead))
        app.store.flush()
        assertEquals(dead, app.store.get("relink-test")?.uri)

        runBlocking { app.library.scan(force = false) }

        val after = app.store.get("relink-test")
        assertTrue("the book disappeared from the library", after != null)
        assertEquals("the record was not repointed at our copy", Uri.fromFile(copy).toString(), after!!.uri)
        assertTrue("the copy is gone", copy.exists())
    }
}
