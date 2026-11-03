package app.kairn.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * L-01: finds the EPUBs that are already on the device. Discovery only - it never imports, hashes or writes,
 * so it can be reasoned about (and tested) without a Store. Two sources: MediaStore plus a direct-storage
 * walk for the files MediaStore does not index, and a SAF tree the user picked.
 */
class EpubSweeper(
    private val resolver: ContentResolver,
    /** Where the device walk starts. Empty is fine: the MediaStore query still runs. */
    private val roots: List<File>,
) {

    /** MediaStore first, then the direct walk. [onFound] may be called more than once for one file. */
    suspend fun sweep(onFound: suspend (Uri) -> Unit) {
        val found = mutableSetOf<String>()
        runCatching {
            val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA)
            val sel = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            resolver.query(MediaStore.Files.getContentUri("external"), proj, sel, arrayOf("%.epub"), null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), c.getLong(idCol).toString())
                    if (found.add(uri.toString())) onFound(uri)
                }
            }
        }
        for (root in roots) runCatching {
            root.listFiles()?.forEach { f -> findEpubs(f, 0, found) { onFound(Uri.fromFile(it)) } }
        }
    }

    /** One picked SAF tree, walked for EPUBs. */
    suspend fun walkTree(root: DocumentFile, onEpub: suspend (DocumentFile) -> Unit) =
        walkTree(root, 0, onEpub)

    private suspend fun walkTree(dir: DocumentFile, depth: Int, onEpub: suspend (DocumentFile) -> Unit) {
        if (depth > MAX_DEPTH) return
        for (d in dir.listFiles()) {
            if (d.isDirectory) walkTree(d, depth + 1, onEpub)
            else if (d.name?.endsWith(".epub", ignoreCase = true) == true) onEpub(d)
        }
    }
}

/** A SAF tree, or a storage tree, can be deep or hand back a loop; neither walk goes past this. */
internal const val MAX_DEPTH = 5

/**
 * The direct-storage walk. Pure [File] so it needs no ContentResolver and is testable on the JVM: an
 * `Android/` directory is skipped (its contents are app-private and enormous), and everything else is
 * depth-capped. [found] dedupes across roots and against whatever MediaStore already reported.
 */
internal suspend fun findEpubs(
    f: File,
    depth: Int = 0,
    found: MutableSet<String> = mutableSetOf(),
    onFound: suspend (File) -> Unit,
) {
    if (depth > MAX_DEPTH || f.name.startsWith("Android")) return
    if (f.isDirectory) {
        f.listFiles()?.forEach { findEpubs(it, depth + 1, found, onFound) }
    } else if (f.isFile && f.name.endsWith(".epub", ignoreCase = true)) {
        if (found.add(f.absolutePath)) onFound(f)
    }
}
