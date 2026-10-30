package app.kairn.data

import org.json.JSONObject

/**
 * Kindle's "My Clippings.txt" (DS-03): the file every Kindle drops in its documents folder, one block per
 * highlight, note or bookmark, separated by a line of ten '='.
 *
 *     The Left Hand of Darkness (Le Guin, Ursula K.)
 *     - Your Highlight on page 42 | Location 631-633 | Added on Monday, 12 March 2018 21:04:11
 *
 *     It is good to have an end to journey toward; but it is the journey that matters, in the end.
 *     ==========
 *
 * Clippings carry no EPUB locator - a Kindle location is meaningless in another rendering - so the importer
 * finds each quote in the book itself and uses that as the place to land (see [ClippingImport]).
 */
data class Clipping(
    val title: String,
    val author: String,
    val quote: String,      // empty for a bookmark
    val note: String,       // empty for a highlight
    val kind: String,       // "highlight" | "note" | "bookmark"
)

object Clippings {

    private val marker = Regex("^\\s*-\\s*Your\\s+(Highlight|Note|Bookmark)\\b", RegexOption.IGNORE_CASE)

    /** Every usable entry. Blocks that carry no text (bookmarks) are dropped: there is nothing to find. */
    fun parse(raw: String): List<Clipping> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        return text.split(Regex("(?m)^\\s*={3,}\\s*$"))
            .mapNotNull(::block)
            .filter { it.quote.isNotBlank() || it.note.isNotBlank() }
    }

    private fun block(chunk: String): Clipping? {
        val lines = chunk.lines().map { it.trim() }.dropWhile { it.isEmpty() }
        if (lines.size < 2) return null
        val head = lines.first()
        if (head.isBlank()) return null
        // "Title (Author)" - the author is the last parenthesised group, and a title may itself contain
        // brackets, so the match is anchored at the end.
        val m = Regex("^(.*)\\s*\\(([^()]*)\\)\\s*$").find(head)
        val title = (m?.groupValues?.get(1) ?: head).trim()
        val author = m?.groupValues?.get(2)?.trim().orEmpty()
        if (title.isBlank()) return null

        val metaIndex = lines.indexOfFirst { marker.containsMatchIn(it) }
        if (metaIndex < 0) return null
        val kind = marker.find(lines[metaIndex])!!.groupValues[1].lowercase()

        // Everything below the metadata line is the clipping. A highlight is one paragraph; a note is the
        // reader's own words; Kindle occasionally repeats the book title above it, which is dropped.
        val body = lines.drop(metaIndex + 1).filter { it.isNotBlank() && it != head }
        val text2 = body.joinToString(" ").trim()
        return when (kind) {
            "note" -> Clipping(title, author, quote = "", note = text2, kind = "note")
            "bookmark" -> Clipping(title, author, quote = "", note = "", kind = "bookmark")
            else -> Clipping(title, author, quote = text2, note = "", kind = "highlight")
        }
    }

    /** Lowercase letters and digits only, so punctuation and spacing cannot stop a match. */
    fun normalise(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Whether a clipping belongs to [book]. A missing author on either side is not a reason to reject. */
    fun matches(c: Clipping, book: Book): Boolean {
        val ct = normalise(c.title)
        val bt = normalise(book.title)
        if (ct.isEmpty() || bt.isEmpty()) return false
        if (!(ct == bt || ct.contains(bt) || bt.contains(ct))) return false
        return authorMatches(c.author, book.author)
    }

    private fun tokens(author: String): List<String> =
        author.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }

    /**
     * Kindle writes "Le Guin, Ursula K." where the EPUB says "Ursula K. Le Guin", so an in-order comparison
     * rejects the right book. Every real word of the shorter name has to appear in the other one instead.
     */
    fun authorMatches(a: String, b: String): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return true
        val (small, big) = if (ta.size <= tb.size) ta to b.lowercase() else tb to a.lowercase()
        return small.all { big.contains(it) }
    }

    /** The queries used to find a clipping in the book, longest first. */
    fun queries(c: Clipping): List<String> {
        val q = c.quote.ifBlank { c.note }.replace(Regex("\\s+"), " ").trim()
        if (q.length < 4) return emptyList()
        return listOf(q.take(60), q.take(30), q.take(15))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
    }

    fun toJson(c: Clipping) = JSONObject().put("t", c.title).put("a", c.author)
        .put("q", c.quote).put("n", c.note).put("k", c.kind)
}
