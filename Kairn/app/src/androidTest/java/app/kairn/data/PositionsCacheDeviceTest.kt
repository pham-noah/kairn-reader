package app.kairn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.KairnApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Locator.Locations
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File

/**
 * The positions cache, on a device: a locator list written now must come back with the hrefs, progressions and
 * chapter titles intact, because the progress slider navigates with the objects that come out of it.
 *
 * This is the one part of the cache that cannot be checked on the JVM - `Locator.toJSON`/`fromJSON` reach
 * `org.json`, which is stubbed in unit tests - so it is pinned here. A corrupt file must read as "no cache"
 * rather than throw, since that path runs inside openBook.
 */
@RunWith(AndroidJUnit4::class)
class PositionsCacheDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: KairnApp get() = inst.targetContext.applicationContext as KairnApp
    private val cache = PositionsCache(inst.targetContext)

    /** A real file with a real size and mtime, because the cache stamps what it stores against both. */
    private fun book(id: String): Book {
        val f = File(inst.targetContext.cacheDir, "$id.epub").apply { writeText("x".repeat(1024)) }
        return Book(id, "Test", "", android.net.Uri.fromFile(f).toString())
    }

    private fun locator(href: String, progression: Double, title: String) = Locator(
        Url(href)!!,
        MediaType.XHTML,
        title,
        Locations(progression = progression),
        Locator.Text(),
    )

    @Test fun a_stored_list_comes_back_with_its_locations_intact() {
        val b = book("positions-roundtrip")
        val stored = listOf(
            locator("ch1.xhtml", 0.0, "One"),
            locator("ch2.xhtml", 0.5, "Two"),
            locator("ch3.xhtml", 1.0, "Three"),
        )
        kotlinx.coroutines.runBlocking { cache.save(b, stored) }

        val loaded = cache.load(b)
        assertNotNull("a saved list must load back", loaded)
        assertEquals(stored.size, loaded!!.size)
        stored.zip(loaded).forEach { (was, now) ->
            assertEquals(was.href.toString(), now.href.toString())
            assertEquals(was.title, now.title)
            assertEquals(was.locations.progression ?: -1.0, now.locations.progression ?: -1.0, 0.0001)
        }
        cache.forget(b.id)
    }

    @Test fun a_file_that_changed_under_the_cache_is_not_trusted() {
        val b = book("positions-stale")
        kotlinx.coroutines.runBlocking { cache.save(b, listOf(locator("ch1.xhtml", 0.0, "One"))) }
        assertNotNull(cache.load(b))

        // Same book record, different bytes on disk: the stored list describes a file that is gone.
        File(android.net.Uri.parse(b.uri).path!!).apply { writeText("y".repeat(2048)) }
        assertNull(cache.load(b))
        cache.forget(b.id)
    }

    @Test fun rubbish_in_the_cache_slot_reads_as_no_cache() {
        val b = book("positions-corrupt")
        kotlinx.coroutines.runBlocking { cache.save(b, listOf(locator("ch1.xhtml", 0.0, "One"))) }
        File(inst.targetContext.filesDir, "positions/${b.id}.json.gz").writeText("not-a-gzip")
        assertNull(cache.load(b))
        cache.forget(b.id)
    }

    @Test fun saving_leaves_nothing_but_the_stored_list() {
        val b = book("positions-no-leftovers")
        // Twice in a row stands in for two opens racing on the same book: the temp names must not collide,
        // and neither write may leave a file behind for prune to find later.
        repeat(2) { kotlinx.coroutines.runBlocking { cache.save(b, listOf(locator("ch1.xhtml", 0.0, "One"))) } }
        val files = File(inst.targetContext.filesDir, "positions").list().orEmpty()
        assertEquals(listOf("${b.id}.json.gz"), files.filter { it.startsWith(b.id) })
        cache.forget(b.id)
    }

    @Test fun prune_takes_the_books_that_left_and_the_temp_files() {
        val kept = book("positions-kept")
        val gone = book("positions-gone")
        kotlinx.coroutines.runBlocking {
            cache.save(kept, listOf(locator("ch1.xhtml", 0.0, "One")))
            cache.save(gone, listOf(locator("ch1.xhtml", 0.0, "One")))
        }
        // What a cancelled save leaves on disk: both the current shape and the older name that pruned to the
        // book's own id, which is the one a suffix-stripping prune would keep forever.
        val tmpDir = File(inst.targetContext.filesDir, "positions")
        val leftCurrent = File(tmpDir, "${kept.id}.12345.tmp").apply { writeText("half") }
        val leftLegacy = File(tmpDir, "${kept.id}.tmp").apply { writeText("half") }

        cache.prune(setOf(kept.id))

        assertEquals(false, leftCurrent.exists())
        assertEquals(false, leftLegacy.exists())
        assertEquals(false, File(inst.targetContext.filesDir, "positions/${gone.id}.json.gz").exists())
        assertNotNull(cache.load(kept))
        cache.forget(kept.id)
    }

    @Test fun forgetting_a_book_takes_its_file() {
        val b = book("positions-forget")
        kotlinx.coroutines.runBlocking { cache.save(b, listOf(locator("ch1.xhtml", 0.0, "One"))) }
        val f = File(inst.targetContext.filesDir, "positions/${b.id}.json.gz")
        assertEquals(true, f.exists())
        cache.forget(b.id)
        assertEquals(false, f.exists())
    }
}
