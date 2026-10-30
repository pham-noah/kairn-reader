package app.kairn.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Locator.Locations
import org.readium.r2.shared.publication.Locator.Text
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * One gzipped text dump per book (`filesDir/search/<id>.json.gz`), so a repeat search greps disk instead of
 * re-parsing every EPUB. Hits keep a real href and progression, so tapping one opens the right chapter.
 * Invalidated by the book file's size and mtime; "Clear cache" in Settings drops the lot.
 */
class SearchIndex(private val ctx: Context, private val store: Store, private val readium: ReadiumHelper) {

    private val dir = File(ctx.filesDir, "search")

    data class Chapter(val href: String, val title: String, val text: String)

    /** A hit from the cache: enough to show it and to open the book there. */
    data class Cached(val href: String, val title: String, val progression: Double, val snippet: String)

    private fun fileFor(id: String) = File(dir, "$id.json.gz")

    private fun stamp(book: Book): Pair<Long, Long>? {
        // DocumentFile cannot stat a file:// book (it speaks DocumentsContract), so stat the copy directly.
        if (book.uri.startsWith("file:")) {
            val f = Uri.parse(book.uri).path?.let(::File) ?: return null
            return if (f.exists()) f.length() to f.lastModified() else null
        }
        val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, Uri.parse(book.uri)) ?: return null
        if (!doc.exists()) return null
        return doc.length() to doc.lastModified()
    }

    /** True when this book has a usable dump for the file it currently points at. */
    fun isFresh(book: Book): Boolean {
        val f = fileFor(book.id)
        if (!f.exists() || f.length() == 0L) return false
        val (size, mtime) = stamp(book) ?: return false
        val meta = runCatching { JSONObject(String(readDump(f))).optJSONObject("meta") }.getOrNull() ?: return false
        return meta.optLong("size") == size && meta.optLong("mtime") == mtime
    }

    /** Reads a book once and writes its dump. Returns false when the book could not be read at all. */
    suspend fun build(book: Book): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val (size, mtime) = stamp(book) ?: return@runCatching false
            val pub = readium.open(Uri.parse(book.uri)) ?: return@runCatching false
            try {
                val chapters = ArrayList<Chapter>()
                var budget = MAX_CHARS
                for (item in pub.readingOrder.take(MAX_CHAPTERS)) {
                    if (budget <= 0) break
                    // Link.href is an Href wrapper; the Url(String) factory bridges it.
                    val hrefUrl = Url(item.href.toString()) ?: continue
                    val loc = Locator(hrefUrl, item.mediaType ?: MediaType.XHTML, null, Locations(), Text())
                    val text = runCatching { pub.content(loc)?.text() }.getOrNull() ?: continue
                    if (text.isBlank()) continue
                    val trimmed = text.take(budget)
                    budget -= trimmed.length
                    val title = runCatching { pub.tableOfContents.find { it.href.toString() == item.href.toString() }?.title }.getOrNull()
                    chapters += Chapter(item.href.toString(), title ?: item.href.toString().substringAfterLast('/'), trimmed)
                }
                if (chapters.isEmpty()) return@runCatching false
                val root = JSONObject()
                    .put("meta", JSONObject().put("size", size).put("mtime", mtime).put("v", 1))
                    .put("chapters", JSONArray(chapters.map { JSONObject().put("href", it.href).put("title", it.title).put("text", it.text) }))
                GZIPOutputStream(fileFor(book.id).outputStream().buffered()).use { it.write(root.toString().toByteArray()) }
                true
            } finally {
                pub.close()
            }
        }.getOrDefault(false)
    }

    /** Case-insensitive substring search over one book's dump. */
    suspend fun search(book: Book, query: String, perBook: Int): List<Cached> = withContext(Dispatchers.IO) {
        val f = fileFor(book.id)
        if (!f.exists()) return@withContext emptyList()
        val chars = runCatching { String(readDump(f)) }.getOrNull() ?: return@withContext emptyList()
        val chapters = runCatching { JSONObject(chars).getJSONArray("chapters") }.getOrNull() ?: return@withContext emptyList()
        val q = query.trim()
        val out = ArrayList<Cached>()
        for (i in 0 until chapters.length()) {
            if (out.size >= perBook) break
            val c = chapters.optJSONObject(i) ?: continue
            val text = c.optString("text")
            if (text.isEmpty()) continue
            var at = text.indexOf(q, ignoreCase = true)
            while (at >= 0 && out.size < perBook) {
                val before = text.substring(maxOf(0, at - 30), at).trimStart()
                val after = text.substring(minOf(text.length, at + q.length), minOf(text.length, at + q.length + 80)).trimEnd()
                out += Cached(
                    href = c.optString("href"),
                    title = c.optString("title"),
                    // Progression is approximate: a hit only has to open near the match.
                    progression = if (text.isEmpty()) 0.0 else at.toDouble() / text.length,
                    snippet = listOf(before, text.substring(at, minOf(text.length, at + q.length)), after).filter { it.isNotBlank() }.joinToString(" "),
                )
                at = text.indexOf(q, at + q.length, ignoreCase = true)
            }
        }
        out
    }

    /** The number of books with a dump, and how much disk they use - shown in Settings. */
    fun stats(): Pair<Int, Long> = dir.listFiles().orEmpty().let { files -> files.size to files.sumOf { it.length() } }

    /** A book left the library: its dump must not outlive it. */
    fun forget(id: String) { runCatching { fileFor(id).delete() } }

    /** Drops dumps for books that are no longer in the library (a delete that raced, or a crash). */
    fun prune(known: Set<String>) {
        dir.listFiles().orEmpty().forEach { f ->
            val id = f.name.removeSuffix(".json.gz")
            if (id !in known) runCatching { f.delete() }
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        dir.delete()
    }

    private fun readDump(f: File): ByteArray = GZIPInputStream(f.inputStream().buffered()).use { it.readBytes() }

    /** Locator for a cached hit, so the reader can open straight at it. */
    fun locator(hit: Cached): Locator? = runCatching {
        val href = Url(hit.href) ?: return null
        Locator(href, MediaType.XHTML, hit.title, Locations(progression = hit.progression), Text())
    }.getOrNull()

    private companion object {
        const val MAX_CHAPTERS = 400
        const val MAX_CHARS = 4_000_000        // ~4 MB of text; a book past this is searched live instead
    }
}
