package app.kairn.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** DS-01 / DEC-12: one zip of the open-format data dir; book files optional. */
class Backup(private val ctx: Context, private val store: Store) {

    suspend fun write(dest: Uri, includeBookFiles: Boolean, onProgress: (Float) -> Unit = {}): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                store.flush()                       // queued writes must be on disk before we zip them
                val files = store.root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toMutableList()
                if (includeBookFiles) files += store.copiesDir.listFiles().orEmpty()
                val out = ctx.contentResolver.openOutputStream(dest, "w")
                    ?: error("Could not open that file for writing.")
                out.use { raw ->
                    ZipOutputStream(raw.buffered()).use { zip ->
                        files.forEachIndexed { i, f ->
                            val rel = if (f.startsWith(store.root)) "data/" + f.relativeTo(store.root).path
                                      else "imported/" + f.name
                            zip.putNextEntry(ZipEntry(rel)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                            onProgress((i + 1f) / files.size)
                        }
                    }
                }
                files.size
            }
        }

    /**
     * Restore makes a safety copy first, then merges (keeps both sides) or replaces.
     *
     * ORDER IS THE POINT: the archive is VALIDATED before anything is deleted. This used to delete every
     * book JSON first and only then open the file the user picked, so choosing "Replace" and pointing at
     * an unrelated zip wiped the library, threw on the first entry, left the in-memory list intact so the
     * UI still showed every book, and reported "your data is unchanged" - with the files gone next launch.
     */
    suspend fun restore(src: Uri, replace: Boolean): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            store.flush()                           // the safety copy must include everything the user just did

            // 1. Validate. Nothing destructive happens until we know this is a Kairn backup. A backup of
            //    an empty library legitimately holds no books, just settings.json, so any "data/" entry is
            //    the test.
            val names = ctx.contentResolver.openInputStream(src)?.use { zipEntryNames(it) }
            require(isKairnBackup(names)) {
                "That file isn't a Kairn backup, so nothing was changed."
            }

            // 2. Safety copy of everything we are about to overwrite.
            val safety = File(ctx.filesDir, "safety-${System.currentTimeMillis()}.zip")
            ZipOutputStream(safety.outputStream().buffered()).use { zip ->
                store.root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("data/" + f.relativeTo(store.root).path))
                    f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            // Keep only the two newest safety copies; every restore used to leave another one behind for good.
            ctx.filesDir.listFiles { f -> f.name.startsWith("safety-") && f.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }

            // 3. Destructive from here on. If this part fails, the message must say so: the pre-restore
            //    data now lives only in the safety copy.
            try {
                if (replace) { store.booksDir.listFiles()?.forEach { it.delete() } }
                ctx.contentResolver.openInputStream(src)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            val name = e.name
                            if (!e.isDirectory && !name.contains("..")) {
                                val bytes = zip.readBytes()
                                when {
                                    name.startsWith("data/books/") -> mergeBook(File(store.root, name.removePrefix("data/")), bytes, replace, store::writeFile)
                                    name == "data/settings.json" -> if (replace) store.writeFile(File(store.root, "settings.json"), String(bytes))
                                    name.startsWith("data/covers/") -> File(store.root, name.removePrefix("data/")).takeIf { replace || !it.exists() }?.writeBytes(bytes)
                                    name.startsWith("imported/") -> File(store.copiesDir, name.removePrefix("imported/")).takeIf { !it.exists() }?.writeBytes(bytes)
                                }
                            }
                            e = zip.nextEntry
                        }
                    }
                }
                store.reload()
            } catch (t: Throwable) {
                throw IllegalStateException("Restore failed part-way. Your original data is in ${safety.name}.", t)
            }
            safety
        }
    }

}

/**
 * Merges one book record. Never loses a note: marks are unioned by id and the newer record wins the
 * scalar fields. An unreadable record on one side must not cost the other one.
 *
 * Top level on purpose: it needs no Context, so a JVM test can call it directly. [write] defaults to a
 * direct atomic write for that reason; Backup passes the Store's queued writer so a restore cannot be
 * overtaken by a pending snapshot.
 */
internal fun mergeBook(
    target: File,
    bytes: ByteArray,
    replace: Boolean,
    write: (File, String) -> Unit = { f, t -> Store.writeAtomic(f, t) },
) {
    if (replace || !target.exists()) { target.writeBytes(bytes); return }
    val mine = runCatching { Book.from(JSONObject(target.readText())) }.getOrNull()
    val theirs = runCatching { Book.from(JSONObject(String(bytes))) }.getOrNull()
    if (mine == null || theirs == null) {
        if (mine == null && theirs != null) target.writeBytes(bytes)   // ours unusable: take theirs
        return                                                        // theirs unusable: keep ours
    }
    val newer = if (theirs.lastRead > mine.lastRead) theirs else mine
    val marks = (mine.marks + theirs.marks).distinctBy { it.id }   // never lose a note
    write(target, newer.copy(
        marks = marks,
        tags = (mine.tags + theirs.tags).distinct(),
        collections = (mine.collections + theirs.collections).distinct(),
    ).toJson().toString(2))
}

/**
 * Whether these zip entries look like one of our backups. This is the check that has to be true before
 * anything is deleted, so it is a named function with its own tests rather than an inline condition.
 *
 * Any "data/" entry is enough: a backup of an EMPTY library legitimately holds settings.json and no books.
 */
internal fun isKairnBackup(names: List<String>?): Boolean =
    names != null && names.any { it.startsWith("data/") }

/**
 * The entry names in a zip stream, or null when the stream cannot be read as a zip at all. Bounded, so a
 * corrupt or hostile file cannot make the walk run forever. The stream is consumed by this call.
 *
 * Note a JDK quirk a test caught: garbage usually yields an EMPTY list here rather than null — only some
 * corrupt streams throw. Both are "not a backup"; [isKairnBackup] handles each.
 */
internal fun zipEntryNames(input: InputStream, limit: Int = 5000): List<String>? = runCatching {
    val names = ArrayList<String>()
    ZipInputStream(input.buffered()).use { zip ->
        var e = zip.nextEntry
        while (e != null && names.size < limit) { names.add(e.name); e = zip.nextEntry }
    }
    names
}.getOrNull()

/** A-03: annotations as Markdown. */
fun Book.annotationsMarkdown(): String = buildString {
    appendLine("# $title"); if (author.isNotBlank()) appendLine("*$author*"); appendLine()
    marks.sortedBy { it.created }.forEach { m ->
        when (m.kind) {
            "bookmark" -> appendLine("- \uD83D\uDD16 Bookmark")
            else -> { if (m.quote.isNotBlank()) appendLine("> ${m.quote.replace("\n", "\n> ")}"); if (m.note.isNotBlank()) appendLine("\n${m.note}"); appendLine() }
        }
    }
}
