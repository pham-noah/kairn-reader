package app.kairn.data

import android.content.Context
import android.net.Uri
import app.kairn.R
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
                    ?: error(ctx.getString(R.string.s_backup_cannot_open))
                out.use { raw ->
                    ZipOutputStream(raw.buffered()).use { zip ->
                        files.forEachIndexed { i, f ->
                            // Forward slashes always: an archive is a format, and the restore path matches on
                            // "data/books/". A backslash from a non-Android JVM would be read as a foreign zip.
                            val rel = if (f.startsWith(store.root)) "data/" + f.relativeTo(store.root).path.replace(File.separatorChar, '/')
                                      else "imported/" + f.name
                            zip.putNextEntry(ZipEntry(rel))
                            if (f.name == "settings.json") {
                                // A catalog password stays on this phone: the zip may be copied to a computer,
                                // a cloud folder or a friend, and a server login is not a preference.
                                zip.write(store.settings.value.withoutSecrets().toJson().toString().toByteArray())
                            } else {
                                f.inputStream().use { it.copyTo(zip) }
                            }
                            zip.closeEntry()
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
     * ORDER IS THE POINT: the archive is VALIDATED before anything is deleted.
     */
    suspend fun restore(src: Uri, replace: Boolean): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            store.flush()                           // the safety copy must include every queued write

            // Validate BEFORE anything is deleted. The archive is copied to scratch and read whole: the
            // central directory has to be there and every entry's bytes must match its own CRC, which a backup
            // cut short by a full disk or a kill cannot do.
            val scratch = File(ctx.cacheDir, "restore-check.zip")
            try {
                val names = runCatching {
                    ctx.contentResolver.openInputStream(src)?.use { i -> scratch.outputStream().use { i.copyTo(it) } }
                    zipContents(scratch).map { it.name }
                }.getOrNull()
                require(isKairnBackup(names)) { ctx.getString(R.string.s_backup_not_kairn) }
                val archivedBooks = names.orEmpty().filter { it.startsWith("data/books/") }.toSet()

                // Safety copy of everything a replace is about to overwrite.
                val safety = File(ctx.filesDir, "safety-${System.currentTimeMillis()}.zip")
                ZipOutputStream(safety.outputStream().buffered()).use { zip ->
                    store.root.walkTopDown().filter { it.isFile }.forEach { f ->
                        zip.putNextEntry(ZipEntry("data/" + f.relativeTo(store.root).path))
                        f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                // Keep only the two newest safety copies.
                ctx.filesDir.listFiles { f -> f.name.startsWith("safety-") && f.name.endsWith(".zip") }
                    ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }

                // Destructive from here on. If this part fails, the message must say so: the pre-restore data
                // now lives only in the safety copy.
                try {
                    // No pre-delete: a replace removes the records the archive does not hold only after it has
                    // been read whole, so a failure in the middle leaves the library untouched.
                    java.util.zip.ZipFile(scratch).use { zip ->
                        for (e in zip.entries()) {
                            val name = e.name
                            if (e.isDirectory || name.contains("..")) continue
                            when {
                                name.startsWith("data/books/") ->
                                    mergeBook(File(store.root, name.removePrefix("data/")), zip.getInputStream(e).use { it.readBytes() }, replace, store::writeFile)
                                // The reading position lives in its own file. A MERGE keeps the newer one: the
                                // records above are merged by lastRead, so writing an old sidecar over a newer
                                // one would put every book back where the backup left it.
                                name.startsWith("data/pos/") ->
                                    mergePositionJson(File(store.root, name.removePrefix("data/")),
                                        String(zip.getInputStream(e).use { it.readBytes() }), replace, store::writeFile)
                                // Merge keeps the settings on this phone; it only adopts the backup's when there
                                // are none (a fresh install restoring onto an empty app).
                                name == "data/settings.json" -> {
                                    val target = File(store.root, "settings.json")
                                    if (replace || !target.exists()) store.writeFile(target, zip.getInputStream(e).use { String(it.readBytes()) })
                                }
                                // A cast is a taste, not a place: a merge keeps the one on this phone and only
                                // takes the archive's when there is none, the same rule the settings follow.
                                name.startsWith("data/cast/") -> {
                                    val target = File(store.root, name.removePrefix("data/"))
                                    if (replace || !target.exists()) unzipInto(zip, e, target)
                                }
                                name.startsWith("data/covers/") || name.startsWith("data/thumbs/") -> {
                                    val target = File(store.root, name.removePrefix("data/"))
                                    if (replace || !target.exists()) unzipInto(zip, e, target)
                                }
                                // A book file is streamed to disk: a 200 MB entry must never be one ByteArray.
                                name.startsWith("imported/") -> {
                                    val target = File(store.copiesDir, name.removePrefix("imported/"))
                                    if (!target.exists()) unzipInto(zip, e, target)
                                }
                            }
                        }
                    }
                    if (replace) {
                        store.flush()
                        store.booksDir.listFiles()?.filterNot { "data/books/${it.name}" in archivedBooks }?.forEach { it.delete() }
                        // A position for a book the archive does not hold would follow the next book to take that id.
                        val kept = archivedBooks.map { it.removePrefix("data/books/").removeSuffix(".json") }.toSet()
                        store.posDir.listFiles()?.filterNot { it.name.removeSuffix(".json") in kept }?.forEach { it.delete() }
                        // Same for a cast: one for a book the archive does not hold would follow the next
                        // book to take that id, and give its characters someone else's voices.
                        store.castDir.listFiles()?.filterNot { it.name.removeSuffix(".json") in kept }?.forEach { it.delete() }
                    }
                    store.reload()
                    // lastSweep describes THIS device's storage, so it never comes out of an archive: a restored
                    // value could hold off the one sweep that re-attaches the books.
                    store.saveSettings { it.copy(lastSweep = 0) }
                    // A replace restore is a mirror of the archive: art whose book is gone is an orphan.
                    if (replace) pruneOrphanArt(store)
                } catch (t: Throwable) {
                    throw IllegalStateException(ctx.getString(R.string.s_restore_failed_partway, safety.name), t)
                }
                safety
            } finally {
                runCatching { scratch.delete() }
            }
        }
    }

    /** One entry written atomically to [target], streamed so a book file is never held in memory. */
    private fun unzipInto(zip: java.util.zip.ZipFile, e: ZipEntry, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        zip.getInputStream(e).use { i -> tmp.outputStream().use { i.copyTo(it) } }
        if (!tmp.renameTo(target)) { runCatching { target.delete() }; tmp.renameTo(target) }
    }

    /** Cover and thumbnail files with no book record left to show them. Only called after a replace. */
    private fun pruneOrphanArt(store: Store) {
        val live = store.books.value.map { it.id }.toSet()
        listOf(store.coversDir, store.thumbsDir).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.name.substringBeforeLast('.') !in live) runCatching { f.delete() }
            }
        }
    }
}

/**
 * Merges one book record.
 * An unreadable record on one side must not cost the other one.
 * Top level so a JVM test can call it without a Context: [write] defaults to a direct atomic write for that reason; Backup passes the Store's queued writer so a restore cannot be overtaken by a pending snapshot.
 */
internal fun mergeBook(
    target: File,
    bytes: ByteArray,
    replace: Boolean,
    write: (File, String) -> Unit = { f, t -> Store.writeAtomic(f, t) },
) {
    // Through the queued atomic writer even here: a truncated record is dropped by reload() without a word.
    if (replace || !target.exists()) { write(target, String(bytes)); return }
    val mine = runCatching { Book.from(JSONObject(target.readText())) }.getOrNull()
    val theirs = runCatching { Book.from(JSONObject(String(bytes))) }.getOrNull()
    if (mine == null || theirs == null) {
        if (mine == null && theirs != null) write(target, String(bytes))   // ours unusable: take theirs
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
 * Merges one position sidecar. [replace] and a sidecar that is not there take the archive as written; a merge
 * keeps whichever side read more recently, the same rule [mergeBook] applies to the record itself.
 * Top level so a JVM test can call it without a Context.
 */
internal fun mergePositionJson(
    target: File,
    archived: String,
    replace: Boolean,
    write: (File, String) -> Unit = { f, t -> Store.writeAtomic(f, t) },
) {
    if (replace || !target.exists()) { write(target, archived); return }
    val theirs = runCatching { JSONObject(archived) }.getOrNull() ?: return      // unusable: keep ours
    val mine = runCatching { JSONObject(target.readText()) }.getOrNull()
    if (mine == null) { write(target, archived); return }                        // ours unusable: take theirs
    if (theirs.optLong("lastRead", 0) > mine.optLong("lastRead", 0)) write(target, archived)
}

/**
 * Whether these zip entries look like one of our backups. This must be true before anything is deleted.
 *
 * A backup of an EMPTY library legitimately holds settings.json and no books, so either is enough - but an
 * entry that would be skipped on the way back in ("..", which no restore writes) must not count as one:
 * otherwise an archive that restores nothing still validates, and a replace then prunes the whole library.
 */
internal fun isKairnBackup(names: List<String>?): Boolean =
    names != null &&
        names.none { it.contains("..") } &&
        names.any { it.startsWith("data/books/") || it == "data/settings.json" }

/**
 * The entry names in a zip stream, or null when the stream cannot be read as a zip at all.
 * Bounded, so a corrupt or hostile file cannot make the walk run forever.
 */
internal fun zipEntryNames(input: InputStream, limit: Int = 5000): List<String>? = runCatching {
    val names = ArrayList<String>()
    ZipInputStream(input.buffered()).use { zip ->
        var e = zip.nextEntry
        while (e != null && names.size < limit) { names.add(e.name); e = zip.nextEntry }
    }
    names
}.getOrNull()

/**
 * Every entry of a whole archive: the central directory has to be present and each entry's bytes must match
 * the size and CRC recorded for it. A killed or full-disk backup fails this, which is what makes a replace
 * restore safe to start.
 */
internal fun zipContents(file: File, limit: Int = 5000): List<ZipEntry> = java.util.zip.ZipFile(file).use { zip ->
    val out = ArrayList<ZipEntry>()
    val buf = ByteArray(8192)
    for (e in zip.entries()) {
        if (out.size >= limit) break
        out += e
        if (e.isDirectory) continue
        val crc = java.util.zip.CRC32()
        var total = 0L
        zip.getInputStream(e).use { s -> while (true) { val n = s.read(buf); if (n < 0) break; crc.update(buf, 0, n); total += n } }
        if (total != e.size || crc.value != e.crc) throw java.util.zip.ZipException("${e.name} is incomplete")
    }
    out
}

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
