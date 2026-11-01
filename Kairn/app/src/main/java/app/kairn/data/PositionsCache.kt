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
 * [FORMAT] is bumped when a change in how pages are laid out makes every stored list wrong: positions are a
 * function of the engine's pagination, not just of the file.
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
            // Write beside the target and rename, so a kill mid-write cannot leave a half file to load.
            val tmp = File(dir, "${book.id}.tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(json.toString().toByteArray()) }
            if (!tmp.renameTo(fileFor(book.id))) tmp.delete()
        }
    }

    /** A book left the library: its list must not outlive it. */
    fun forget(id: String) { runCatching { fileFor(id).delete() } }

    /** Drops lists for books no longer in the library. */
    fun prune(known: Set<String>) {
        dir.listFiles().orEmpty().forEach { f ->
            val id = f.name.removeSuffix(".json.gz").removeSuffix(".tmp")
            if (id !in known) runCatching { f.delete() }
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        dir.delete()
    }

    /** Books with a stored list, and the disk they use - shown in Settings next to the search cache. */
    fun stats(): Pair<Int, Long> =
        dir.listFiles().orEmpty().filter { it.name.endsWith(".json.gz") }
            .let { files -> files.size to files.sumOf { it.length() } }

    companion object {
        /** Bump when a pagination change makes stored positions wrong rather than merely old. */
        const val FORMAT = 1
    }
}
