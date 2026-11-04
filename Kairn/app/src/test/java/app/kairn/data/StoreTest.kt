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

    @Test fun `a position save writes only the sidecar, and a reload puts it back on the record`() {
        val s = store()
        s.put(Book(id = "a", title = "T", author = "", uri = "u", marks = listOf(Mark("m1", "highlight", 0, "q", "", "{}", 1L))))
        s.flush()
        val record = File(s.booksDir, "a.json").readText()
        s.savePosition("a") { it.copy(locator = "{\"href\":\"c1\"}", progress = 0.5f, lastRead = 42L, status = Status.READING) }
        s.flush()
        assertEquals("the record must not be rewritten by a page turn", record, File(s.booksDir, "a.json").readText())
        assertTrue("the sidecar holds the position", File(s.posDir, "a.json").exists())
        assertEquals(0.5f, s.get("a")!!.progress)
        s.reload()
        assertEquals("a cold read restores the position", 0.5f, s.get("a")!!.progress)
        assertEquals(Status.READING, s.get("a")!!.status)
        assertEquals("the mark is still there", 1, s.get("a")!!.marks.size)
    }

    @Test fun `removing a book takes its sidecar with it`() {
        val s = store()
        s.put(Book(id = "a", title = "T", author = "", uri = "u"))
        s.savePosition("a") { it.copy(progress = 0.3f) }
        s.flush()
        assertTrue(File(s.posDir, "a.json").exists())
        s.remove("a")
        s.flush()
        assertTrue("no position may outlive its book", !File(s.posDir, "a.json").exists())
    }

    @Test fun `an atomic write replaces the record and leaves no temp file behind`() {
        val f = File(tmp.root, "settings.json")
        Store.writeAtomic(f, "one")
        Store.writeAtomic(f, "two")
        assertEquals("two", f.readText())
        assertEquals("no .tmp may survive a successful write", 0, tmp.root.listFiles().orEmpty().count { it.name.endsWith(".tmp") })
    }

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

    /**
     * A book that leaves the library takes its cached files with it. This used to be one nullable field
     * assigned from KairnApp: if that assignment were ever lost, a removed book's search dump and positions
     * stayed on disk with nothing to say so. Now every cache registers, and this holds the contract.
     */
    @Test fun `removing a book tells every registered listener, before the record goes`() {
        val s = store()
        val seen = mutableListOf<String>()
        var recordWasStillReadable = false
        s.onBookRemoved { id ->
            seen += "search:$id"
            recordWasStillReadable = s.get(id) != null
        }
        s.onBookRemoved { id -> seen += "positions:$id" }
        s.put(Book(id = "x", title = "T", author = "", uri = "u"))

        s.remove("x")

        assertEquals(listOf("search:x", "positions:x"), seen)
        assertTrue("a listener may still need the record", recordWasStillReadable)
        assertTrue("and the record is gone afterwards", s.get("x") == null)
    }

    /** Nothing registered, nothing to tell: a removal must not depend on anyone listening. */
    @Test fun `removing a book with no listeners is quiet`() {
        val s = store()
        s.put(Book(id = "y", title = "T", author = "", uri = "u"))

        s.remove("y")

        assertTrue(s.get("y") == null)
    }
}
