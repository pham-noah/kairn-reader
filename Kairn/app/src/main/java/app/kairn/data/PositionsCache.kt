package app.kairn.data

import android.content.Context
import androidx.core.net.toUri

import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * positions() walks the whole publication to measure it, which costs seconds on a real book and returns the
 * same answer every time the same file is opened. One gzipped JSON list per book
 * (`filesDir/positions/<id>.json.gz`), so only the first open of a book pays.
 *
 * Validity is the book id (a content hash) plus the file's size and mtime, the same stamp SearchIndex uses.
 * The reader's own settings are deliberately not part of the key: Readium's positions come from the
 * publication's content, not from how it is laid out here. Measured with text at 100% and then at 242%, the
 * same book returned 212 positions both times. [FORMAT] is bumped only when Readium's own way of counting
 * positions changes.
 */
class PositionsCache(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "positions")

    private fun fileFor(id: String) = File(dir, "$id.json.gz")

    private fun stamp(book: Book): Pair<Long, Long>? {
        // DocumentFile cannot stat a file:// book (it speaks DocumentsContract), so stat the copy directly.
        if (book.uri.startsWith("file:")) {
            val f = book.uri.toUri().path?.let(::File) ?: return null
            return if (f.exists()) f.length() to f.lastModified() else null
        }
        val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, book.uri.toUri()) ?: return null
        if (!doc.exists()) return null
        return doc.length() to doc.lastModified()
    }

    /** The stored positions for this book, or null when there are none worth trusting. */
    fun load(book: Book): List<Locator>? = runCatching {
        val f = fileFor(book.id)
        if (!f.exists() || f.length() == 0L) return null
        val (size, mtime) = stamp(book) ?: return null
        val json = JSONObject(String(GZIPInputStream(f.inputStream().buffered()).use { it.readBytes() }))
        if (json.optInt("v") != FORMAT) return null
        if (json.optLong("size") != size || json.optLong("mtime") != mtime) return null
        val list = json.optJSONArray("positions") ?: return null
        val out = ArrayList<Locator>(list.length())
        for (i in 0 until list.length()) {
            val loc = Locator.fromJSON(list.getJSONObject(i)) ?: return null
            out += loc
        }
        if (out.isEmpty()) return null
        out
    }.getOrNull()

    suspend fun save(book: Book, positions: List<Locator>) {
        if (positions.isEmpty()) return
        runCatching {
            val (size, mtime) = stamp(book) ?: return
            if (!dir.exists()) dir.mkdirs()
            val json = JSONObject().put("v", FORMAT).put("size", size).put("mtime", mtime)
            val arr = JSONArray()
            positions.forEach { arr.put(it.toJSON()) }
            json.put("positions", arr)
            // Write beside the target and rename, so a kill mid-write cannot leave a half file to load. The
            // name carries a counter because two opens of the same book can save at once and must not share it.
            val tmp = File(dir, "${book.id}.${System.nanoTime()}.tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(json.toString().toByteArray()) }
            if (!tmp.renameTo(fileFor(book.id))) tmp.delete()
        }
    }

    /** A book left the library: its list must not outlive it. */
    fun forget(id: String) { runCatching { fileFor(id).delete() } }

    /** Drops lists for books no longer in the library, and any temp file a cancelled save left behind. */
    fun prune(known: Set<String>) {
        dir.listFiles().orEmpty().forEach { f ->
            if (f.name.endsWith(".tmp")) { runCatching { f.delete() }; return@forEach }
            val id = f.name.removeSuffix(".json.gz")
            if (id !in known) runCatching { f.delete() }
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        dir.delete()
    }

    /** Which books have a stored list. Settings counts them together with the search dumps, without double-counting. */
    fun ids(): Set<String> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(".json.gz") }
            .map { it.name.removeSuffix(".json.gz") }.toSet()

    /** Books with a stored list, and the disk they use - shown in Settings next to the search cache. */
    fun stats(): Pair<Int, Long> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(".json.gz") }
            .let { files -> files.size to files.sumOf { it.length() } }

    companion object {
        /** Bump when Readium's own way of counting positions changes, making stored lists wrong, not just old. */
        const val FORMAT = 1
    }
}
