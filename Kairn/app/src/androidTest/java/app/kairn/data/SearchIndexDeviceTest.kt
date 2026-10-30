package app.kairn.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.KairnApp
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The search cache, on a device: a book is read once, its text lands in a dump, the dump answers a search, and
 * a book whose file has changed is not trusted.
 *
 * The query is taken from the dump itself, so the test cannot pass by matching a phrase that happens to be in
 * a fixture - it proves the stored text is really searchable.
 */
@RunWith(AndroidJUnit4::class)
class SearchIndexDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: KairnApp get() = inst.targetContext.applicationContext as KairnApp
    private val index get() = app.searchIndex

    private fun publish(asset: String, id: String): Book {
        val dest = File(app.store.copiesDir, "$id.epub")
        inst.context.assets.open(asset).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        val book = Book(id, id, "", android.net.Uri.fromFile(dest).toString())
        app.store.put(book)
        app.store.flush()
        return book
    }

    private fun dumpOf(id: String): JSONObject? {
        val dir = File(inst.targetContext.filesDir, "search")
        val f = File(dir, "$id.json.gz")
        if (!f.exists()) return null
        val text = GZIPInputStream(f.inputStream().buffered()).use { String(it.readBytes()) }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    @Test
    fun aBookIsReadOnceAndTheDumpAnswersSearches() = runBlocking {
        val book = publish("paging-ltr.epub", "idx-1")
        index.clear()
        assertFalse("nothing cached yet", index.isFresh(book))

        assertTrue("the book could not be indexed", index.build(book))
        assertTrue("a built dump must count as fresh", index.isFresh(book))

        val dump = dumpOf(book.id)
        val chapters = dump?.optJSONArray("chapters")
        assertTrue("the dump has chapters", chapters != null && chapters.length() > 0)

        // A phrase that is genuinely in the stored text, mid-chapter so the snippet has neighbours.
        val text = chapters!!.optJSONObject(0).optString("text")
        val at = (text.length / 2).coerceAtMost(text.length - 8)
        val query = text.substring(at, (at + 5).coerceAtMost(text.length)).trim()
        assertTrue("fixture text is too short to test with", query.length >= 3)

        val hits = index.search(book, query, perBook = 3)
        assertTrue("a phrase from the dump must be found", hits.isNotEmpty())
        assertTrue("a hit carries a snippet", hits.first().snippet.isNotBlank())
        assertEquals("one book in the cache", 1, index.stats().first)

        // The file changed under us: the dump must be treated as stale, not silently reused.
        File(book.uri.removePrefix("file://")).appendBytes(ByteArray(8))
        assertFalse("a changed file invalidates the dump", index.isFresh(book))

        index.clear()
        assertEquals("clearing empties the cache", 0, index.stats().first)
    }
}
