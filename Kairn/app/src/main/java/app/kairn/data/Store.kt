package app.kairn.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Plain JSON on disk (DEC-8).
 * Every write goes to a temp file and is renamed over the original, so a crash or power loss mid-write can never leave a half-written file.
 * WRITE PATH (off the main thread).
 */
@Stable
class Store private constructor(val root: File, val copiesDir: File) {
    /** Production: the app's private files dir. */
    constructor(ctx: Context) : this(File(ctx.filesDir, "data"), File(ctx.filesDir, "imported"))

    val booksDir = File(root, "books").apply { mkdirs() }
    val coversDir = File(root, "covers").apply { mkdirs() }
    val thumbsDir = File(root, "thumbs").apply { mkdirs() }     // small pre-decoded cover thumbnails
    private val settingsFile = File(root, "settings.json")

    init { copiesDir.mkdirs() }        // a constructor param, so nothing else creates it

    val books = MutableStateFlow<List<Book>>(emptyList())
    val settings = MutableStateFlow(AppSettings())

    // ---- serial, coalescing writer. Declared before init, which calls reload() -> flush(). ----
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kairn-store") }
    private val lock = Any()
    private val pending = LinkedHashMap<File, () -> Unit>()     // newest job per file wins
    private var draining = false                                 // guarded by [lock]

    private fun enqueue(key: File, job: () -> Unit) {
        synchronized(lock) {
            pending[key] = job
            if (draining) return                                 // the running drain will pick it up
            draining = true
            // Submitted INSIDE the lock: flush() reads `draining` under the same lock, so a submit outside
            // it could let a flush return while the write was still pending - which is what a backup zips.
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
     * overtaken by a still-queued snapshot for that file. [reload] flushes, so a caller that reloads
     * straight after is still ordered correctly.
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
    private val loadedFlow = MutableStateFlow(false)

    /** True once the first [load] has finished. Plain read; use [loaded] from a composable. */
    val isLoaded: Boolean get() = loadedOnce.isCompleted

    /**
     * [isLoaded] as a StateFlow, for screens that draw a placeholder until the first read finishes.
     * An EMPTY library emits nothing on [books], so a plain boolean read in composition would leave the
     * library stuck on its "loading" layout forever on a first run - the read finishing is itself an event.
     */
    val loaded: StateFlow<Boolean> = loadedFlow

    /** The first read of the library from disk, off the main thread. */
    suspend fun load() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { reload() }        // a failure must not leave every caller waiting forever
        loadedOnce.complete(Unit)
        loadedFlow.value = true
    }

    /**
     * Suspends until the first [load] finishes. A cold write without it would act on an EMPTY library:
     * addBook would not find the existing record and would `put` a fresh one over it, losing that book's
     * marks and reading position, and a scan would re-add what is already known.
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
        runCatching { AppSettings.from(JSONObject(settingsFile.readText())) }
            .onSuccess { settings.value = it }
            // Only a REAL parse failure warns: no settings file yet is the normal first launch, not an error.
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
     * Read-modify-write, ATOMIC: @Synchronized so a position save (reader) and a mark added at the same
     * moment cannot both start from the same old Book and drop one of the changes.
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
            // Copying back rather than deleting `target` and retrying the rename: a second rename failure
            // (ENOSPC, EACCES, a SAF-backed path) would otherwise lose the previous good file.
            runCatching { target.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } } }
                .onSuccess { tmp.delete() }
                .onFailure { Log.w("KairnStore", "could not replace ${target.name}; the previous file is intact", it) }
        }
    }
}
