package app.kairn

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.AppSettings
import app.kairn.data.Backup
import app.kairn.data.Book
import app.kairn.data.Mark
import app.kairn.data.Store
import app.kairn.data.ThemeId
import app.kairn.data.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A backup nobody has ever restored is a zip file with a nice name.
 *
 * Everything here has only ever been exercised by writing the archive. This reads it back into an empty store
 * and asks whether the library that comes out is the library that went in - books, where each one was left,
 * what was written in the margins, and the settings that decide how the app looks on the next launch. That is
 * the one bug class a reader must never meet: their notes gone, and no way to tell whether the backup or the
 * restore ate them.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(name: String): Store =
        Store.forTesting(File(tmp.root, name).also { it.mkdirs() }).also { runBlocking { it.load() } }

    private fun sample(): Store {
        val s = store("a")
        s.put(Book(
            "b1", "The Peregrine", "J. A. Baker", "file:///peregrine.epub",
            status = Status.READING, progress = 0.42f, collections = listOf("Favourites"),
            tags = listOf("nature"), series = "Nature Writing", seriesIndex = 2.0,
            marks = listOf(
                Mark("m1", "highlight", 0xFFFFD54F.toInt(), "the salt marsh and", "", "{\"href\":\"ch1.xhtml\"}", 1_700_000_100_000),
                Mark("m2", "note", 0xFF7EE0C3.toInt(), "a hawk", "He hunts by waiting.", "{\"href\":\"ch2.xhtml\"}", 1_700_000_200_000),
            ),
        ))
        s.put(Book("b2", "Anna Karenina", "Leo Tolstoy", "file:///ak.epub", status = Status.FINISHED, progress = 1f))
        s.saveSettings { it.copy(theme = ThemeId.SEPIA, grid = false, sort = "AUTHOR", sortFlipped = true) }
        s.flush()
        return s
    }

    @Test fun a_backup_comes_back_whole() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = sample()

        val out = File(tmp.root, "kairn.zip")
        val written = runBlocking { Backup(ctx, a).write(Uri.fromFile(out), includeBookFiles = false) }
        assertTrue("write failed: $written", written.isSuccess && out.length() > 0)
        // A shot must assert its own premise, and so must a test: an archive of zero entries proves nothing.
        val entries = java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name } }
        // The archive is one JSON per book and a settings file, not one lump: assert the real premise.
        assertTrue("no book in the archive: $entries", entries.any { it.endsWith("books/b1.json") })
        assertTrue("no settings in the archive: $entries", entries.any { it.endsWith("settings.json") })

        val b = store("b")
        assertTrue("the fresh store was not empty", b.books.value.isEmpty())
        val back = runBlocking { Backup(ctx, b).restore(Uri.fromFile(out), replace = true) }
        assertTrue("restore failed: $back", back.isSuccess)

        b.reload()
        assertEquals("book count", a.books.value.size, b.books.value.size)

        val got = b.get("b1") ?: error("b1 did not survive the round trip; got ${b.books.value.map { it.id }}")
        assertEquals("title", "The Peregrine", got.title)
        assertEquals("author", "J. A. Baker", got.author)
        assertEquals("status", Status.READING, got.status)
        assertEquals("position", 0.42f, got.progress, 0.001f)
        assertEquals("collections", listOf("Favourites"), got.collections)
        assertEquals("tags", listOf("nature"), got.tags)
        assertEquals("series", "Nature Writing", got.series)
        assertEquals("series index", 2.0, got.seriesIndex)

        assertEquals("marks", 2, got.marks.size)
        val note = got.marks.firstOrNull { it.kind == "note" } ?: error("the note is gone: ${got.marks.map { it.kind }}")
        assertEquals("note text", "He hunts by waiting.", note.note)
        assertEquals("note quote", "a hawk", note.quote)
        assertEquals("note locator", "{\"href\":\"ch2.xhtml\"}", note.locator)
        assertEquals("note time", 1_700_000_200_000, note.created)
        assertTrue("highlight lost its colour", got.marks.any { it.kind == "highlight" && it.color == 0xFFFFD54F.toInt() })

        val cfg = b.settings.value
        assertEquals("theme", ThemeId.SEPIA, cfg.theme)
        assertEquals("view mode", false, cfg.grid)
        assertEquals("sort", "AUTHOR", cfg.sort)
        assertEquals("sort direction", true, cfg.sortFlipped)
        // Settings written before the sort was one are not a crash, they are the default order.
        assertEquals("default sort", "RECENT", AppSettings().sort)
    }
}
