package app.kairn.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.search.search
import java.util.UUID

/**
 * Brings Kindle highlights and notes into the library (DS-03). A Kindle location means nothing inside an
 * EPUB, so each quote is searched for in the book and the first hit becomes its locator. A quote that is not
 * found is counted, never guessed at.
 */
class ClippingImport(
    private val ctx: android.content.Context,
    private val store: Store,
    private val readium: ReadiumHelper,
) {
    data class Result(
        val imported: Int,
        val alreadyHad: Int,
        val notFound: Int,
        val books: Int,
        val unmatched: Int,       // clippings whose book is not in the library
    )

    suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
    }

    /** [onProgress] is (done, total) over the clippings that matched a book. */
    suspend fun run(
        clippings: List<Clipping>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        store.awaitLoaded()
        val books = store.books.value.filter { !it.broken && !it.missing }
        // A book wins a clipping only once: the first book whose title and author both fit.
        val byBook = LinkedHashMap<String, MutableList<Clipping>>()
        var unmatched = 0
        for (c in clippings) {
            val book = books.firstOrNull { Clippings.matches(c, it) }
            if (book == null) unmatched++ else byBook.getOrPut(book.id) { mutableListOf() } += c
        }

        var imported = 0
        var alreadyHad = 0
        var notFound = 0
        var done = 0
        val total = byBook.values.sumOf { it.size }

        for ((bookId, list) in byBook) {
            val book = store.get(bookId) ?: continue
            val pub = readium.open(Uri.parse(book.uri))
            if (pub == null) {
                notFound += list.size
                done += list.size
                onProgress(done, total)
                continue
            }
            try {
                val seen = book.marks.map { Clippings.normalise(it.quote.ifBlank { it.note }) }.toMutableSet()
                val added = mutableListOf<Mark>()
                for (c in list) {
                    onProgress(done, total)
                    val key = Clippings.normalise(c.quote.ifBlank { c.note })
                    if (key.isNotEmpty() && !seen.add(key)) { alreadyHad++; done++; continue }
                    val locator = locate(pub, c)
                    if (locator == null) { notFound++; done++; continue }
                    added += Mark(
                        id = UUID.randomUUID().toString(),
                        kind = if (c.kind == "note") "note" else "highlight",
                        color = MINT,
                        quote = c.quote,
                        note = c.note,
                        locator = locator.toJSON().toString(),
                        created = System.currentTimeMillis(),
                    )
                    imported++
                    done++
                }
                if (added.isNotEmpty()) store.update(bookId) { it.copy(marks = it.marks + added) }
            } finally {
                runCatching { pub.close() }
            }
        }
        onProgress(total, total)
        Result(imported, alreadyHad, notFound, byBook.size, unmatched)
    }

    /** The first locator the book itself answers with, or null. Several query lengths, longest first. */
    private suspend fun locate(pub: org.readium.r2.shared.publication.Publication, c: Clipping): Locator? {
        for (q in Clippings.queries(c)) {
            val iter = runCatching { pub.search(q) }.getOrNull() ?: continue
            try {
                val page = iter.next().getOrNull() ?: continue
                page.locators.firstOrNull()?.let { return it }
            } finally {
                runCatching { iter.close() }
            }
        }
        return null
    }

    private companion object {
        /** The mint highlight colour as ARGB: K.mint from the theme, kept here so the data layer has no UI import. */
        const val MINT = 0xFF7EE0C3.toInt()
    }
}
