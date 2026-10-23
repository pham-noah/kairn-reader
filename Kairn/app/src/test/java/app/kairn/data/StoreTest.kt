package app.kairn.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JVM tests for the Store against a temp dir (Store.forTesting), no device. */
class StoreTest {

    @get:Rule val tmp = TemporaryFolder()

    /**
     * A Store that has already done its first read. `load()` is what the app runs off the main thread at
     * startup, and `addBook`/`scan` BLOCK on it - so a test that skipped this would hang rather than fail.
     */
    private fun store() = Store.forTesting(tmp.root).also { runBlocking { it.load() } }

    @Test fun `a queued write lands on disk, and flush waits for it`() {
        val s = store()
        s.put(Book(id = "a", title = "T", author = "", uri = "u"))
        s.flush()
        assertTrue("the record file must exist", File(s.booksDir, "a.json").exists())
        assertTrue(s.books.value.any { it.id == "a" })
    }

    @Test fun `ten updates coalesce into the newest snapshot`() {
        val s = store()
        s.put(Book(id = "a", title = "0", author = "", uri = "u"))
        for (i in 1..10) s.update("a") { it.copy(title = "$i") }
        s.flush()
        assertEquals("10", s.get("a")?.title)
        assertEquals("10", Book.from(JSONObject(File(s.booksDir, "a.json").readText()))!!.title)
    }

    @Test fun `an unchanged record is not rewritten`() {
        val s = store()
        val b = Book(id = "a", title = "T", author = "", uri = "u")
        s.put(b); s.flush()
        val f = File(s.booksDir, "a.json")
        val before = f.lastModified()
        Thread.sleep(30)
        s.put(b)                       // identical record
        s.flush()
        assertEquals("no write for an identical record", before, f.lastModified())
    }

    @Test fun `a delete is not overtaken by a pending write for the same file`() {
        val s = store()
        s.put(Book(id = "a", title = "T", author = "", uri = "u"))
        s.remove("a")
        s.flush()
        assertTrue("the file must not come back", !File(s.booksDir, "a.json").exists())
        assertTrue(s.books.value.none { it.id == "a" })
    }

    @Test fun `saving an unchanged settings value writes nothing`() {
        val s = store()
        s.saveSettings { it.copy(theme = ThemeId.LIGHT) }
        s.flush()
        val f = File(s.root, "settings.json")
        assertTrue(f.exists())
        val before = f.lastModified()
        Thread.sleep(30)
        s.saveSettings { it }          // identical value
        s.flush()
        assertEquals(before, f.lastModified())
    }

    /**
     * The library draws a placeholder until the first read finishes. An EMPTY library emits nothing on
     * `books` (the flow de-duplicates equal values), so that finishing has to be observable on its own -
     * otherwise a first run stays on the placeholder instead of reaching the empty state.
     */
    @Test fun `the load gate flips once the first read is done`() {
        val s = Store.forTesting(tmp.root)
        assertFalse("not loaded before the first read", s.loaded.value)
        runBlocking { s.load() }
        assertTrue("loaded after the first read", s.loaded.value)
    }
}
