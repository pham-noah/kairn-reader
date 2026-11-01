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

    @Test fun forgetting_a_book_takes_its_file() {
        val b = book("positions-forget")
        kotlinx.coroutines.runBlocking { cache.save(b, listOf(locator("ch1.xhtml", 0.0, "One"))) }
        val f = File(inst.targetContext.filesDir, "positions/${b.id}.json.gz")
        assertEquals(true, f.exists())
        cache.forget(b.id)
        assertEquals(false, f.exists())
    }
}
