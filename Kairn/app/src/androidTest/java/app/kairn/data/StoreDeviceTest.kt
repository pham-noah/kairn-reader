package app.kairn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The JVM suite drives Store against a temp dir. This one drives it against a real Android filesystem,
 * which is the only place the atomic temp+rename path and the serial writer actually run as shipped.
 */
@RunWith(AndroidJUnit4::class)
class StoreDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun base() = File(ctx.cacheDir, "store-device-${System.nanoTime()}")

    @Test fun a_record_written_here_is_on_disk_and_comes_back_after_a_reload() {
        val base = base()
        val store = Store.forTesting(base)
        val book = Book("device1", "Device Book", "Someone", "content://example/book.epub")
        store.put(book)
        store.flush()                              // the writer is async; the assertion is about the file

        val file = File(store.booksDir, "device1.json")
        assertTrue("record file missing", file.exists())
        assertTrue("json is empty", file.readText().contains("Device Book"))
        assertEquals(0, File(store.booksDir, "device1.json.tmp").let { if (it.exists()) 1 else 0 })

        val second = Store.forTesting(base)        // a fresh instance = what a cold start does
        runBlocking { second.load() }
        assertEquals("Device Book", second.get("device1")?.title)
    }

    @Test fun removing_a_book_takes_its_file_and_cover_off_the_disk() {
        val base = base()
        val store = Store.forTesting(base)
        store.put(Book("gone", "Gone", "", "content://example/gone.epub"))
        store.coverFile("gone").writeText("x")
        store.flush()
        store.remove("gone")
        store.flush()
        assertTrue(!File(store.booksDir, "gone.json").exists())
        assertTrue(!store.coverFile("gone").exists())
    }
}
