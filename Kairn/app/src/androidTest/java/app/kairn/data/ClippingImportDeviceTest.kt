package app.kairn.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import java.io.File

/**
 * The Kindle import against a REAL EPUB and Readium's REAL search - the one thing a JVM test cannot prove,
 * because the whole placement strategy is "search the book and take the hit".
 *
 * The fixture (paging-ltr.epub) has three chapters whose text is `wN_0 wN_1 ...`, so "Chapter 1 Chapter 1"
 * exists in exactly one of them: finding that string and checking the mark's href IS the assertion that the
 * quote landed in the right chapter, not merely somewhere.
 */
@RunWith(AndroidJUnit4::class)
class ClippingImportDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    // The fixture EPUB lives in the TEST APK's assets, not the app's: targetContext.assets is the app under
    // test and answers FileNotFoundException for it.
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun fixtureStore(): Pair<Store, ReadiumHelper> {
        val store = Store.forTesting(File(ctx.cacheDir, "clip-${System.nanoTime()}"))
        runBlocking { store.load() }
        val file = File(ctx.cacheDir, "fixture-${System.nanoTime()}.epub")
        testAssets.open("paging-ltr.epub").use { i -> file.outputStream().use { o -> i.copyTo(o) } }
        val readium = ReadiumHelper(ctx)
        runBlocking { Library(ctx, store, readium).addBook(Uri.fromFile(file), null, "Paging Fixture") }
        return store to readium
    }

    @Test
    fun a_quote_is_placed_in_the_chapter_that_actually_holds_it() = runBlocking {
        val (store, readium) = fixtureStore()
        // The book's title comes from the EPUB metadata, not from the name it was imported under - a clipping
        // is matched against what the store holds, so the test uses the same string the reader would see.
        val title = store.books.value.single().title
        assertTrue(title.isNotBlank())

        val clipper = ClippingImport(ctx, store, readium)
        // "Chapter 2" sits in c2's <h1> and in no other chapter, so the href proves the placement is real.
        val found = Clipping(title, "", "Chapter 2", "", "highlight")
        val missing = Clipping(title, "", "a sentence that is not in this book at all", "", "highlight")
        val r = clipper.run(listOf(found, missing))

        assertEquals(1, r.imported)
        assertEquals(1, r.notFound)
        assertEquals(0, r.unmatched)

        val mark = store.books.value.single().marks.single()
        assertEquals("highlight", mark.kind)
        assertEquals("Chapter 2", mark.quote)
        val href = Locator.fromJSON(JSONObject(mark.locator))!!.href.toString()
        assertTrue("placed in chapter 2, not just somewhere: $href", href.contains("c2"))
    }

    @Test
    fun importing_the_same_file_twice_adds_nothing() = runBlocking {
        val (store, readium) = fixtureStore()
        val clipper = ClippingImport(ctx, store, readium)
        val title = store.books.value.single().title
        val one = listOf(Clipping(title, "", "w1_5", "", "highlight"))

        val first = clipper.run(one)
        val second = clipper.run(one)

        assertEquals(1, first.imported)
        assertEquals(0, second.imported)
        assertEquals(1, second.alreadyHad)
        assertEquals(1, store.books.value.single().marks.size)
    }

    @Test
    fun a_clipping_for_a_book_that_is_not_in_the_library_is_counted_not_dropped() = runBlocking {
        val (store, readium) = fixtureStore()
        val clipper = ClippingImport(ctx, store, readium)
        val r = clipper.run(listOf(Clipping("Some Other Book", "Nobody", "a line from elsewhere", "", "highlight")))

        assertEquals(0, r.imported)
        assertEquals(1, r.unmatched)
        assertTrue(store.books.value.single().marks.isEmpty())
    }
}
