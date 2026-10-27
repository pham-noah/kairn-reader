package app.kairn.ui

import app.kairn.data.Book
import app.kairn.data.Mark
import app.kairn.data.ReaderPrefs
import app.kairn.data.Status
import app.kairn.data.Store
import org.readium.r2.shared.publication.Locator

/**
 * Every write the reader makes to the book's own record, in one place.
 * Every call is cheap on the caller's thread: Store updates memory and queues the disk write itself.
 */
internal class ReaderWriter(private val store: Store, private val bookId: String) {

    /** R-09. Reaching the end is what finishes a book; nothing else sets FINISHED. */
    fun savePosition(loc: Locator) {
        val atEnd = (loc.locations.totalProgression ?: 0.0) >= 0.99
        store.update(bookId) {
            it.copy(
                locator = loc.toJSON().toString(),
                progress = (loc.locations.totalProgression ?: 0.0).toFloat(),
                lastRead = System.currentTimeMillis(),
                broken = false,
                status = when {
                    atEnd -> Status.FINISHED
                    it.status == Status.NONE || it.status == Status.WANT -> Status.READING
                    else -> it.status      // a manual status (and a re-read of a finished book) is left alone
                },
            )
        }
    }

    /** R-10: the reading settings are per book. */
    fun savePrefs(p: ReaderPrefs) = store.update(bookId) { it.copy(prefs = p) }

    fun addMark(m: Mark) = store.update(bookId) { it.copy(marks = it.marks + m) }

    fun deleteMark(m: Mark) = store.update(bookId) { it.copy(marks = ReaderLogic.withoutMark(it.marks, m.id)) }

    /** Undo. Idempotent, so a double tap on the undo action cannot duplicate the mark. */
    fun restoreMark(m: Mark) = store.update(bookId) { b ->
        if (b.marks.any { it.id == m.id }) b else b.copy(marks = b.marks + m)
    }

    /** The bookmark toggle removes every bookmark in the chapter it is pressed in. */
    fun removeBookmarksIn(href: String, hrefOf: (Mark) -> String?) = store.update(bookId) { b ->
        b.copy(marks = b.marks.filterNot { it.kind == "bookmark" && hrefOf(it) == href })
    }

    /** N-04: the file failed to open. The record stays, so nothing the reader wrote is lost. */
    fun markBroken() = store.get(bookId)?.let { store.put(it.copy(broken = true)) }

    /** A-02: new text for an existing note. */
    fun updateNote(id: String, text: String) = store.update(bookId) { b ->
        b.copy(marks = b.marks.map { if (it.id == id) it.copy(note = text) else it })
    }

    /** Removing the record. The EPUB file itself is never touched - that is the library's rule. */
    fun removeBook() = store.remove(bookId)

    /** The measured word count, kept with the book so the count is a one-time cost per book. */
    fun saveWords(n: Int) = store.update(bookId) { if (it.words == n) it else it.copy(words = n) }

    /** A record read straight from the store, for callers that need the current copy. */
    fun book(): Book? = store.get(bookId)
}
