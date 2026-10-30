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
     * ORDER IS THE POINT: the archive is VALIDATED before anything is deleted.
     */
    suspend fun restore(src: Uri, replace: Boolean): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            store.flush()                           // the safety copy must include every queued write

            // 1. Validate. Nothing destructive happens until we know this is a Kairn backup. A backup of
            val names = ctx.contentResolver.openInputStream(src)?.use { zipEntryNames(it) }
            require(isKairnBackup(names)) { ctx.getString(R.string.s_backup_not_kairn) }

            // 2. Safety copy of everything we are about to overwrite.
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
                                    // Merge keeps the settings on this phone; it only adopts the backup's when there
                                    // are none (a fresh install restoring onto an empty app).
                                    name == "data/settings.json" -> {
                                        val target = File(store.root, "settings.json")
                                        if (replace || !target.exists()) store.writeFile(target, String(bytes))
                                    }
                                    name.startsWith("data/covers/") -> File(store.root, name.removePrefix("data/")).takeIf { replace || !it.exists() }?.writeBytes(bytes)
                                    name.startsWith("data/thumbs/") -> File(store.root, name.removePrefix("data/")).takeIf { replace || !it.exists() }?.writeBytes(bytes)
                                    name.startsWith("imported/") -> File(store.copiesDir, name.removePrefix("imported/")).takeIf { !it.exists() }?.writeBytes(bytes)
                                }
                            }
                            e = zip.nextEntry
                        }
                    }
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
        }
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
 * Whether these zip entries look like one of our backups. This must be true before anything is deleted.
 *
 * Any "data/" entry is enough: a backup of an EMPTY library legitimately holds settings.json and no books.
 */
internal fun isKairnBackup(names: List<String>?): Boolean =
    names != null && names.any { it.startsWith("data/") }

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
