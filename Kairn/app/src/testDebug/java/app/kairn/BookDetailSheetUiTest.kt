package app.kairn

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.Book
import app.kairn.data.Mark
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.ui.screens.BookDetailContent
import app.kairn.ui.screens.sizeLabel
import app.kairn.ui.theme.KairnTheme
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The book's own page, drawn on the JVM. What matters here is not pixels: it is that a book with a series
 * says so, that a book without a description says so instead of drawing an empty box, and that a button
 * reaches the store rather than doing nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BookDetailSheetUiTest {

    @get:Rule val compose = createComposeRule()

    private fun newStore(): Store = Store.forTesting(Files.createTempDirectory("kairn-detail").toFile())

    private fun draw(
        store: Store,
        book: Book,
        onOpen: () -> Unit = {},
        onExport: () -> Unit = {},
        onRemove: () -> Unit = {},
        size: Long = 0L,
    ) {
        compose.setContent {
            KairnTheme {
                var expanded by remember { mutableStateOf(false) }
                // Follow the store, as the sheet itself does: a button that writes must move the label.
                val books by store.books.collectAsStateWithLifecycle()
                val live = books.firstOrNull { it.id == book.id } ?: book
                BookDetailContent(
                    book = live, store = store, size = size, expanded = expanded, wpm = 240,
                    onExpand = { expanded = !expanded },
                    onExport = onExport,
                    onToggleFinished = {
                        store.update(live.id) {
                            it.copy(status = if (it.status == Status.FINISHED) Status.READING else Status.FINISHED)
                        }
                    },
                    onOpen = onOpen, onEdit = {}, onRemove = onRemove,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun a_series_book_says_which_part_it_is() {
        val store = newStore()
        val book = Book(id = "g1", title = "Guards! Guards!", author = "Pratchett", uri = "file:///g.epub",
            series = "Discworld", seriesIndex = 8.0)
        store.put(book)
        draw(store, book)
        compose.onNodeWithText("Discworld 8").assertExists()
    }

    @Test fun a_book_with_no_description_says_so_instead_of_drawing_nothing() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = newStore()
        val book = Book(id = "g2", title = "Bare", author = "", uri = "file:///b.epub")
        store.put(book)
        draw(store, book)
        compose.onNodeWithText(ctx.getString(R.string.s_no_description)).assertExists()
        // The author, the publisher, the date, the language and the size all say it: any of them will do.
        assertTrue(compose.onAllNodesWithText(ctx.getString(R.string.s_not_listed)).fetchSemanticsNodes().size >= 4)
    }

    @Test fun the_description_is_drawn_and_can_be_expanded() {
        val store = newStore()
        val book = Book(id = "g3", title = "Long", author = "A", uri = "file:///l.epub",
            desc = "A city in crisis. ".repeat(20), publisher = "Gollancz", language = "en")
        store.put(book)
        draw(store, book)
        compose.onNodeWithText("A city in crisis. ".repeat(20).trim(), substring = true).assertExists()
        compose.onNodeWithText("Gollancz").assertExists()
        compose.onNodeWithText("en").assertExists()
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Less").assertExists()
    }

    @Test fun mark_as_finished_reaches_the_store_and_flips_the_button() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = newStore()
        val book = Book(id = "g4", title = "Done soon", author = "A", uri = "file:///d.epub")
        store.put(book)
        draw(store, book)
        compose.onNodeWithText(ctx.getString(R.string.s_mark_as_finished)).performClick()
        compose.waitForIdle()
        assertEquals(Status.FINISHED, store.get("g4")?.status)
        // The sheet reads the store, so the same composition now offers the way back.
        compose.onNodeWithText(ctx.getString(R.string.s_mark_as_reading)).assertExists()
    }

    @Test fun the_marks_line_counts_only_what_the_book_has() {
        val store = newStore()
        val book = Book(id = "g5", title = "Marked", author = "A", uri = "file:///m.epub",
            marks = listOf(
                Mark("1", "highlight", 0, "q", "", "{}", 1),
                Mark("2", "highlight", 0, "q", "", "{}", 1),
                Mark("3", "note", 0, "q", "a thought", "{}", 1),
            ))
        store.put(book)
        draw(store, book)
        compose.onNodeWithText("2 highlights · 1 note").assertExists()
    }

    @Test fun export_is_offered_only_when_there_is_something_to_export() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = newStore()
        val plain = Book(id = "g6", title = "Plain", author = "A", uri = "file:///p.epub")
        store.put(plain)
        draw(store, plain)
        compose.onNodeWithText(ctx.getString(R.string.s_export_notes)).assertDoesNotExist()
    }

    @Test fun a_file_size_reads_as_one() {
        assertEquals("0 B", sizeLabel(0))
        assertEquals("500 B", sizeLabel(500))
        assertEquals("2 KB", sizeLabel(2048))
        assertEquals("1.5 MB", sizeLabel((1.5 * 1024 * 1024).toLong()))
        assertTrue(sizeLabel(3L * 1024 * 1024 * 1024).endsWith("GB"))
    }
}
