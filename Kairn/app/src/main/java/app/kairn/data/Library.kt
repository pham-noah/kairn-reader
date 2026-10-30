package app.kairn.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.kairn.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Import + folder watching (L-01, L-02, L-03, L-05, L-06). */
class Library(private val ctx: Context, private val store: Store, private val readium: ReadiumHelper) {

    private fun legacyIdFor(uri: String) =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /**
     * An id from the file's content (first 256 KB), so moving or renaming a file keeps the same book and
     * its position and notes stay attached. Falls back to a location hash only when the file cannot be read.
     */
    private suspend fun contentId(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val md = MessageDigest.getInstance("SHA-1")
                val head = ByteArray(256 * 1024)
                val read = ins.read(head)
                if (read > 0) md.update(head, 0, read)
                md.digest().joinToString("") { "%02x".format(it) }.take(16)
            }
        }.getOrNull()
    }

    /** Moves a location-hashed book onto its content id, keeping marks, status and prefs. */
    private fun migrateId(oldId: String, newId: String, uri: Uri, folder: String?) {
        val b = store.get(oldId) ?: return
        if (oldId == newId) return
        store.put(b.copy(id = newId, uri = uri.toString(), folder = folder ?: b.folder))
        val cover = store.coverFile(oldId)
        if (cover.exists()) cover.renameTo(store.coverFile(newId))
        val thumb = store.thumbFile(oldId)     // moved too, so the grid does not need the ensureThumbs re-decode
        if (thumb.exists()) thumb.renameTo(store.thumbFile(newId))
        store.remove(oldId)
    }

    /** Adds one book. Returns the book (possibly marked broken) so the UI can show N-04. */
    suspend fun addBook(uri: Uri, folder: String?, displayName: String? = null): Book = withContext(Dispatchers.IO) {
        store.awaitLoaded()             // see Store.awaitLoaded: without this a cold import overwrites a known book
        // Same location and already known: no hashing needed (Store.put also skips identical records).
        store.findByUri(uri.toString())?.let { return@withContext if (!it.missing) it else it.copy(missing = false).also(store::put) }

        val fallback = displayName ?: uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".epub") ?: ctx.getString(R.string.s_untitled)
        val contentId = contentId(uri)
        val legacyId = legacyIdFor(uri.toString())
        val id = contentId ?: legacyId

        // Same content is already in the library: this file is one we know, at a new location.
        store.get(id)?.let { known ->
            val moved = known.copy(uri = uri.toString(), folder = folder ?: known.folder, missing = false)
            store.put(moved)
            return@withContext moved
        }

        // Older location-hashed entry for this exact file: upgrade it in place, keeping mark and position.
        if (contentId != null && store.get(legacyId) != null) {
            migrateId(legacyId, contentId, uri, folder)
            return@withContext store.get(contentId)!!
        }

        val pub = readium.open(uri)
        val book = if (pub == null) {
            Book(id, fallback, "", uri.toString(), folder, broken = true)
        } else {
            val b = Book(
                id = id,
                title = pub.metadata.title?.takeIf { it.isNotBlank() } ?: fallback,
                author = pub.metadata.authors.joinToString(", ") { it.name },
                uri = uri.toString(), folder = folder,
            )
            readium.saveCover(pub, store, id)
            // The word count is a property of the file, measured once here so every open is instant.
            val words = readium.countWords(pub)
            pub.close()
            b.copy(words = words)
        }
        store.put(book)
        book
    }

    /** L-02: picker import. Keeps the grant so the book can be reopened later. */
    suspend fun importPicked(uri: Uri): Book {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // A picked file's permission belongs to this install: after a reinstall or a restore of records only,
        // the record points at an uri nothing can open. Keep our own copy, as "Open with" does.
        return importShared(uri)
    }

    /** L-03: "Open with". Other apps' grants are temporary, so keep a private copy. */
    suspend fun importShared(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = (DocumentFile.fromSingleUri(ctx, uri)?.name ?: "book-${System.currentTimeMillis()}.epub")
            .substringAfterLast('/').substringAfterLast('\\')    // a display name must never escape copiesDir
        val dest = File(store.copiesDir, name)
        // N-04: a provider that denies or fails mid-copy must not throw out of the caller's lifecycleScope.
        val copied = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { i.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!copied) {
            runCatching { dest.delete() }
            return@withContext Book(
                contentId(uri) ?: legacyIdFor(uri.toString()), name.removeSuffix(".epub"), "",
                uri.toString(), null, broken = true,
            )
        }
        val destUri = Uri.fromFile(dest).toString()
        val before = store.books.value.map { it.id to it.uri }.toMap()
        val book = addBook(Uri.fromFile(dest), null, name.removeSuffix(".epub"))
        // The book was already known: the record now points at this copy, so the previous private copy is an
        // orphan. Deleting only THIS book's previous file keeps a concurrent Wi-Fi upload safe.
        val previous = before[book.id]
        if (previous != null && previous != book.uri) {
            val old = Uri.parse(previous).path?.let { File(it) }
            if (old != null && old.parentFile == store.copiesDir) runCatching { old.delete() }
        }
        return@withContext book
    }


    /**
     * A book whose uri died but whose file is in our own folder is not missing: point the record at the copy.
     * This is what an update or a restore of records only needs - no scan, no dialog. Cheap: one exists() per
     * book, and only for records that do not already resolve.
     */
    private fun relinkOwnedCopies() {
        val copies = store.copiesDir.listFiles()?.associateBy { it.name } ?: return
        var changed = false
        for (b in store.books.value) {
            if (b.broken) continue
            val u = Uri.parse(b.uri)
            // Fast path first: our own copy, checked with one stat and no provider round trip.
            val ours = u.scheme == "file" && u.path?.let { File(it).parentFile == store.copiesDir } == true
            val alive = if (ours) File(u.path!!).exists()
                        else if (u.scheme == "file") u.path?.let { File(it).exists() } == true
                        else DocumentFile.fromSingleUri(ctx, u)?.exists() == true
            if (alive) continue
            val name = u.lastPathSegment ?: continue
            val copy = copies[name] ?: continue
            store.put(b.copy(uri = Uri.fromFile(copy).toString()))
            changed = true
        }
        if (changed) { store.flush(); store.reload() }
    }

    fun addFolder(tree: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = DocumentFile.fromTreeUri(ctx, tree)?.name ?: ctx.getString(R.string.s_folder)
        store.saveSettings { s ->
            if (s.folders.any { it.uri == tree.toString() }) s
            else s.copy(folders = s.folders + WatchedFolder(tree.toString(), name))
        }
    }

    /**
     * L-01: add new books, mark vanished ones as missing (never delete), clear the flag when they return.
     * With auto-detection on it also sweeps the device for EPUBs already there.
     */
    suspend fun scan(force: Boolean = true) {
        store.awaitLoaded()             // a scan against an unloaded library re-adds everything it finds
        // force=false skips a scan that is running or was just done; no caller ever runs two at once.
        if (!force && (scanLock.isLocked || SystemClock.elapsedRealtime() - lastScanEnd < SCAN_MIN_INTERVAL_MS)) return
        scanLock.withLock {
            scanOnce(force)
            lastScanEnd = SystemClock.elapsedRealtime()
        }
    }

    private val scanLock = Mutex()
    @Volatile private var lastScanEnd = -SCAN_MIN_INTERVAL_MS
    private companion object {
        const val SCAN_MIN_INTERVAL_MS = 30_000L
        const val SWEEP_EVERY_MS = 24 * 60 * 60 * 1000L
    }


    private suspend fun scanOnce(force: Boolean = false) = withContext(Dispatchers.IO) {
        val seen = mutableSetOf<String>()
        relinkOwnedCopies()

        // 1. Watched folders picked in settings (survive reboots).
        for (f in store.settings.value.folders.filter { it.enabled }) {
            val root = DocumentFile.fromTreeUri(ctx, Uri.parse(f.uri)) ?: continue
            if (!root.canRead()) continue
            walk(root) { doc ->
                val b = addBook(doc.uri, f.uri, doc.name?.removeSuffix(".epub"))
                seen += b.id
            }
        }

        // 2. Device sweep: every EPUB already on the device. It queries MediaStore and walks external
        // storage, so it runs once a day rather than on every resume - the watched folders above are cheap
        // and stay live. "Find books on this device" in Settings forces it (scan(force = true)).
        val s0 = store.settings.value
        // A book that cannot be found makes the sweep due now, not in a day: after a restore onto a fresh
        // install the records are back but their files are not, and the sweep is what re-attaches them.
        val sweepDue = sweepIsDue(force, s0.lastSweep, store.books.value.any { it.missing }, System.currentTimeMillis())
        if (s0.autoDetect && sweepDue) {
            sweep { found ->
                val b = addBook(found, null, found.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".epub"))
                seen += b.id
            }
            store.saveSettings { it.copy(lastSweep = System.currentTimeMillis()) }
        }

        for (b in store.books.value) {
            val gone = if (b.folder != null) {
                val folderOn = store.settings.value.folders.any { it.uri == b.folder && it.enabled }
                folderOn && b.id !in seen
            } else {
                val u = Uri.parse(b.uri)
                // path is nullable: a bare "file:" uri must not take the whole scan down.
                if (u.scheme == "file") u.path?.let { File(it).exists() } != true
                else DocumentFile.fromSingleUri(ctx, u)?.exists() != true
            }
            if (gone != b.missing) store.put(b.copy(missing = gone))
        }
    }

    /**
     * Backfills the small pre-scaled thumbnail for any book that has a full cover but no thumb yet. Import
     * already writes a thumbnail (ReadiumHelper.saveCover) and a cell falls back to the full cover, so this
     * is an optimisation, called once after the library is on screen.
     */
    suspend fun backfillThumbs() = withContext(Dispatchers.IO) { ensureThumbs() }

    private fun ensureThumbs() {
        store.books.value.forEach { b ->
            val thumb = store.thumbFile(b.id)
            val cover = store.coverFile(b.id)
            if (!thumb.exists() && cover.exists()) {
                runCatching {
                    val bmp = android.graphics.BitmapFactory.decodeFile(cover.path) ?: return@forEach
                    writeThumb(bmp, thumb)
                    bmp.recycle()
                }
            }
        }
    }

    /** Finds EPUBs already on the device: MediaStore first, then a best-effort direct-storage walk. */
    private suspend fun sweep(onFound: suspend (Uri) -> Unit) {
        val found = mutableSetOf<String>()
        runCatching {
            val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DATA)
            val sel = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), proj, sel, arrayOf("%.epub"), null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), c.getLong(idCol).toString())
                    if (found.add(uri.toString())) onFound(uri)
                }
            }
        }
        val roots = listOfNotNull(Environment.getExternalStorageDirectory(), ctx.getExternalFilesDir(null))
        for (root in roots) runCatching {
            root.listFiles()?.forEach { scanDir(it, 0, found, onFound) }
        }
    }

    private suspend fun scanDir(dir: File, depth: Int, found: MutableSet<String>, onFound: suspend (Uri) -> Unit) {
        if (depth > 5 || dir.name.startsWith("Android")) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) scanDir(f, depth + 1, found, onFound)
            else if (f.isFile && f.name.endsWith(".epub", ignoreCase = true)) {
                val u = Uri.fromFile(f)
                if (found.add(u.toString())) onFound(u)
            }
        }
    }

    /** Android 11+ hides non-media files from MediaStore unless the app can see "all files". */
    fun needsAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    /** Opens the system "All files access" screen so the sweep can actually see EPUBs. */
    fun requestAllFilesAccess() = runCatching {
        // Leaving for the system settings and returning: the onResume scan must NOT be throttled, or the
        // books the new permission unlocks would not appear until 30 s later.
        lastScanEnd = -SCAN_MIN_INTERVAL_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private suspend fun walk(dir: DocumentFile, onEpub: suspend (DocumentFile) -> Unit) {
        for (d in dir.listFiles()) {
            if (d.isDirectory) walk(d, onEpub)
            else if (d.name?.endsWith(".epub", ignoreCase = true) == true) onEpub(d)
        }
    }
}

/**
 * Whether the device sweep runs on this pass: forced, never run, a day old, or a book is missing. A missing
 * book counts as due because after a restore onto a fresh install the records are back and the files are not,
 * and the sweep is what re-attaches them.
 */
internal fun sweepIsDue(
    force: Boolean,
    lastSweep: Long,
    anyMissing: Boolean,
    now: Long,
    everyMs: Long = 86_400_000L,
    missingEveryMs: Long = 3_600_000L,
) = force || lastSweep == 0L || now - lastSweep > everyMs || (anyMissing && now - lastSweep > missingEveryMs)
