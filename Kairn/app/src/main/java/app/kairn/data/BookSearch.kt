package app.kairn.data

import android.content.Context
import android.net.Uri
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search

/**
 * Finds a phrase across the whole library.
 *
 * Books are opened one at a time and every hit is handed to [emit] as it is found, so the list fills in while
 * the rest of the library is still being read instead of waiting on the slowest book. Each book is closed
 * before the next one opens: a search must not leave nine publications resident.
 *
 * A full scan is linear in the library, so the caller cancels this when the query changes and shows
 * [onProgress] meanwhile.
 */
object BookSearch {

    data class Hit(val book: Book, val locator: org.readium.r2.shared.publication.Locator, val snippet: String)

    suspend fun run(
        ctx: Context,
        books: List<Book>,
        query: String,
        perBook: Int = 6,
        index: SearchIndex? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        emit: (Hit) -> Unit,
    ) {
        val q = query.trim()
        if (q.length < 3) return
        val searchable = books.filterNot { it.missing || it.broken }
        searchable.forEachIndexed { position, book ->
            // A book with a fresh text dump is grepped instead of parsed: the second search across the
            // library is disk-only, and each book is opened at most once in its life (plus once per change).
            val cached = index?.takeIf { it.isFresh(book) }?.search(book, q, perBook).orEmpty()
            if (cached.isNotEmpty() || index?.isFresh(book) == true) {
                for (c in cached) index?.locator(c)?.let { emit(Hit(book, it, c.snippet)) }
                onProgress(position + 1, searchable.size)
                return@forEachIndexed
            }
            runCatching {
                val pub = ReadiumHelper(ctx).open(Uri.parse(book.uri)) ?: return@runCatching
                try {
                    val iter = pub.search(q) ?: return@runCatching
                    try {
                        var taken = 0
                        while (taken < perBook) {
                            val page = iter.next().getOrNull() ?: break
                            if (page.locators.isEmpty()) break
                            for (locator in page.locators) {
                                emit(Hit(book, locator, snippet(locator)))
                                if (++taken >= perBook) break
                            }
                        }
                    } finally {
                        iter.close()
                    }
                } finally {
                    pub.close()
                }
            }
            // Cache it on the way out, so this book never has to be parsed for a search again.
            runCatching { index?.let { if (!it.isFresh(book)) it.build(book) } }
            onProgress(position + 1, searchable.size)
        }
    }

    /** The matched words with a little of what surrounds them, on one line. */
    private fun snippet(locator: org.readium.r2.shared.publication.Locator): String {
        val text = locator.text
        val before = text.before.orEmpty().takeLast(30).trimStart()
        val hit = text.highlight.orEmpty()
        val after = text.after.orEmpty().take(80).trimEnd()
        return listOf(before, hit, after).filter { it.isNotBlank() }.joinToString(" ")
    }
}
