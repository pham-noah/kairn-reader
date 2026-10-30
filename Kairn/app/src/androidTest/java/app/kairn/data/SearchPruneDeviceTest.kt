package app.kairn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.KairnApp
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A book's search dump must not outlive the book. Deleting a book used to leave `<id>.json.gz` on disk, which
 * the cache-size row in Settings then reported as cache nobody could explain.
 */
@RunWith(AndroidJUnit4::class)
class SearchPruneDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: KairnApp get() = inst.targetContext.applicationContext as KairnApp
    private val dir: File get() = File(inst.targetContext.filesDir, "search").apply { mkdirs() }

    @After fun clean() { dir.listFiles().orEmpty().forEach { it.delete() } }

    private fun dump(id: String): File = File(dir, "$id.json.gz").apply { writeText("x") }

    @Test fun removingABookRemovesItsDump() {
        val f = dump("gone-book")
        assertTrue(f.exists())

        app.store.put(Book("gone-book", "Gone", "", "file:///nope.epub"))
        app.store.flush()
        app.store.remove("gone-book")
        app.store.flush()

        assertFalse("the dump of a removed book is still on disk", f.exists())
    }

    @Test fun pruneDropsOnlyUnknownBooks() {
        val keep = dump("kept-book")
        val orphan = dump("orphan-book")

        app.searchIndex.prune(setOf("kept-book"))

        assertTrue("a live book's dump was deleted", keep.exists())
        assertFalse("an orphan dump survived", orphan.exists())
    }
}
