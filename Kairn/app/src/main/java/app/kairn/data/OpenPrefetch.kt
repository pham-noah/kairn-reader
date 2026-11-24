package app.kairn.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Publication
import java.util.concurrent.ConcurrentHashMap

/**
 * One book, opened before the reader exists.
 *
 * Tapping a cover starts an Activity, and that Activity then asks Readium to open the file - two pieces of work with
 * nothing in common, run one after the other, so every open cost the sum. Warming the publication at the moment of
 * the tap runs the open underneath the Activity launch instead of after it, and the reader takes the book that is
 * already open if one is waiting.
 *
 * Exactly one book is warmed at a time and exactly one take succeeds: the entry is removed on take, so a publication
 * can never be handed to two readers, and a tap that never becomes a reader is closed here instead of holding a zip
 * handle for the life of the process. Everything is best-effort - a failed warm just means the reader opens the book
 * itself, exactly as it did before.
 */
object OpenPrefetch {
    private val warmed = ConcurrentHashMap<String, Deferred<Publication?>>()

    /** Start opening [uri] off the main thread. Any earlier warm that was never taken is closed first. */
    fun warm(scope: CoroutineScope, uri: String, open: suspend () -> Publication?) {
        if (warmed.containsKey(uri)) return
        for (k in warmed.keys.toList()) {
            val d = warmed.remove(k) ?: continue
            // Not cancelled: the open is already in flight, and cancelling it mid-parse is how a partially built
            // publication would be left holding the zip. Waiting for it and closing is one wasted open, once.
            scope.launch(Dispatchers.IO) { runCatching { d.await()?.close() } }
        }
        warmed[uri] = scope.async(Dispatchers.IO) { runCatching { open() }.getOrNull() }
    }

    /** The warmed publication for [uri], or null when there is none. Consumed - a second call returns null. */
    suspend fun take(uri: String): Publication? {
        val d = warmed.remove(uri) ?: return null
        return runCatching { d.await() }.getOrNull()
    }

    /** Close every publication that was warmed and never taken. */
    suspend fun discardAll() {
        for (k in warmed.keys.toList()) {
            val d = warmed.remove(k) ?: continue
            runCatching { d.await()?.close() }
        }
    }
}
