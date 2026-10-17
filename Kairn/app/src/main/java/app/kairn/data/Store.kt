package app.kairn.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Plain JSON on disk (DEC-8). Every write goes to a temp file and is renamed over the
 * original, so a crash or power loss mid-write can never leave a half-written file
 * ("the user's data is never lost").
 *
 * WRITE PATH (off the main thread). The public API is unchanged and non-suspending, but nothing here
 * touches the disk on the caller's thread any more. A mutation updates the in-memory StateFlow
 * immediately (the UI sees it at once) and queues the disk write on one serial writer thread:
 *  - writes are SERIALISED (one thread), so two writes to the same file can never interleave;
 *  - writes are COALESCED per file: if a book is updated 10 times while the writer is busy, only the
 *    newest snapshot is serialised and written. Books are immutable, so the JSON is built lazily on
 *    the writer thread - the caller never pays for toJson();
 *  - deletes go through the same queue, so a delete can never be overtaken by an older pending write
 *    (which would resurrect the file).
 * Code that reads files directly (Backup, restore) must call [flush] first.
 *
 * @Stable because Compose otherwise infers it UNSTABLE: it exposes `File` properties (`root`,
 * `coversDir`, ...), and `File` is an unstable type, which made `store` an unstable parameter on
 * every screen - so no screen taking a Store could ever skip. The promise is true: the instance is
 * created once and its identity never changes; the parts screens actually observe are the
 * `books`/`settings` StateFlows, which are collected into Compose state.
 */
@Stable
class Store private constructor(val root: File, val copiesDir: File) {
    /** Production: the app's private files dir. */
    constructor(ctx: Context) : this(File(ctx.filesDir, "data"), File(ctx.filesDir, "imported"))

    val booksDir = File(root, "books").apply { mkdirs() }
    val coversDir = File(root, "covers").apply { mkdirs() }
    val thumbsDir = File(root, "thumbs").apply { mkdirs() }     // small pre-decoded cover thumbnails
    private val settingsFile = File(root, "settings.json")

    init { copiesDir.mkdirs() }        // a constructor param now, so nothing creates it for us

    val books = MutableStateFlow<List<Book>>(emptyList())
    val settings = MutableStateFlow(AppSettings())

    // ---- serial, coalescing writer. Declared BEFORE init: init calls reload(), which calls flush(). ----
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kairn-store") }
    private val lock = Any()
    private val pending = LinkedHashMap<File, () -> Unit>()     // newest job per file wins
    private var draining = false                                 // guarded by [lock]

    private fun enqueue(key: File, job: () -> Unit) {
        synchronized(lock) {
            pending[key] = job
            if (draining) return                                 // the running drain will pick it up
            draining = true
            // Submitted INSIDE the lock. flush() reads `draining` under this same lock, so with the submit
            // outside it a flush could see draining=true, queue an empty runnable ahead of the drain, and
            // return while the write was still pending - which is what a backup zips.
            io.execute(::drain)
        }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val iter = pending.entries.iterator()
                if (iter.hasNext()) { val e = iter.next(); iter.remove(); e.value }
                else { draining = false; null }
            } ?: return
            // A failed write (disk full, ...) must not kill the writer; the in-memory state stays valid.
            runCatching(job).onFailure { Log.w("KairnStore", "write failed", it) }
        }
    }

    /**
     * Writes a whole file through the SAME serial writer as every other write, so a restore cannot be
     * overtaken by a still-queued snapshot for that file (which is what a direct Store.writeAtomic let
     * happen). [reload] flushes, so a caller that reloads straight after is still ordered correctly.
     */
    fun writeFile(target: File, text: String) = enqueue(target) { writeAtomic(target, text) }

    /**
     * Blocks until every write queued BEFORE this call is on disk. Cheap no-op when idle.
     * Blocking: call from a background thread only (Backup already runs on Dispatchers.IO).
     */
    fun flush() {
        if (synchronized(lock) { draining }) io.submit(Runnable {}).get()
    }

    private val loadedOnce = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** True once the first [load] has finished. */
    val isLoaded: Boolean get() = loadedOnce.isCompleted

    /**
     * The first read of the library from disk, OFF the main thread. It used to run synchronously inside
     * `init`, i.e. inside Application.onCreate, reading and parsing every book file before the first
     * frame - the main cost of a cold start on a big library.
     */
    suspend fun load() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { reload() }        // a failure must not leave every caller waiting forever
        loadedOnce.complete(Unit)
    }

    /**
     * Suspends until the first [load] finishes. Anything that would otherwise act on an EMPTY library has
     * to await this: addBook would not find the existing record and would `put` a fresh one over it -
     * losing that book's marks and reading position - and a scan would re-add what is already known.
     */
    suspend fun awaitLoaded() = loadedOnce.await()

    /** Re-reads everything from disk. Flushes first so queued writes are not lost or overwritten. */
    @Synchronized
    fun reload() {
        flush()
        books.value = booksDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { Book.from(JSONObject(f.readText())) }.getOrNull()
        }
        // Keep the previous settings on a parse failure instead of replacing them with defaults: the next
        // saveSettings would then persist the reset, so one malformed key would wipe theme, folders and
        // the onboarded flag for good.
        runCatching { AppSettings.from(JSONObject(settingsFile.readText())) }
            .onSuccess { settings.value = it }
            // Only a REAL parse failure warns: no settings file yet is the normal first launch, not an error.
            // (The unit tests caught the unguarded version: android.util.Log throws in a JVM test.)
            .onFailure { if (settingsFile.exists()) Log.w("KairnStore", "settings.json could not be read; keeping the current settings", it) }
    }

    fun get(id: String) = books.value.firstOrNull { it.id == id }

    /** Lookup by location. Used on scan so an unchanged file costs no hashing. */
    fun findByUri(uri: String) = books.value.firstOrNull { it.uri == uri }

    fun coverFile(id: String) = File(coversDir, "$id.jpg")

    /** Small pre-scaled cover used by the Library grid. Decoding this is cheap; the full cover is not. */
    fun thumbFile(id: String) = File(thumbsDir, "$id.jpg")

    @Synchronized
    fun put(b: Book) {
        if (get(b.id) == b) return                               // identical record: nothing to write or emit
        val file = File(booksDir, "${b.id}.json")
        enqueue(file) { writeAtomic(file, b.toJson().toString(2)) }
        books.update { list -> list.filterNot { it.id == b.id } + b }
    }

    /**
     * Read-modify-write, now ATOMIC: @Synchronized so a position save (reader) and a mark added at the
     * same moment cannot both start from the same old Book and drop one of the changes.
     */
    @Synchronized
    fun update(id: String, f: (Book) -> Book) { get(id)?.let { put(f(it)) } }

    @Synchronized
    fun remove(id: String) {
        books.update { l -> l.filterNot { it.id == id } }
        // Queued like writes, so a pending older write for the same file cannot bring it back.
        for (f in listOf(File(booksDir, "$id.json"), coverFile(id), thumbFile(id))) enqueue(f) { f.delete() }
    }

    @Synchronized
    fun saveSettings(f: (AppSettings) -> AppSettings) {
        val s = f(settings.value)
        if (s == settings.value) return                          // unchanged: no serialise, no write (as put() does)
        enqueue(settingsFile) { writeAtomic(settingsFile, s.toJson().toString(2)) }
        settings.value = s
    }

    companion object {
        /** Tests: everything under one temp directory instead of a Context's filesDir. */
        fun forTesting(baseDir: File) = Store(File(baseDir, "data"), File(baseDir, "imported"))

        fun writeAtomic(target: File, text: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (tmp.renameTo(target)) return
            // The old code deleted `target` and retried the rename, so if the retry also failed (the reason
            // the first one did - ENOSPC, EACCES, a SAF-backed path) the previous good file was gone and the
            // new data sat in an unreferenced .tmp. Copying back is not atomic, but it cannot lose the old
            // file, which is the promise that matters here.
            runCatching { target.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } } }
                .onSuccess { tmp.delete() }
                .onFailure { Log.w("KairnStore", "could not replace ${target.name}; the previous file is intact", it) }
        }
    }
}
