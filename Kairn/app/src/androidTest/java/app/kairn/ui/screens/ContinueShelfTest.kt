package app.kairn.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.data.Book
import app.kairn.data.Store
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Continue reading shelf. The point of the shelf is that a book you were IN appears twice - once above,
 * once in the grid - while a book you never opened appears only in the grid. Counts, therefore, not presence:
 * "Continue reading" being on screen says nothing about what it holds.
 */
@RunWith(AndroidJUnit4::class)
class ContinueShelfTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store(): Store {
        val s = Store.forTesting(File(ctx.cacheDir, "shelf-${System.nanoTime()}"))
        runBlocking { s.load() }
        return s
    }

    private fun screen(store: Store) = compose.setContent {
        LibraryScreen(
            store = store, shelf = null, onShelf = {},
            onOpen = {}, onAdd = {}, onPickFolder = {}, onPickBook = {},
        )
    }

    @Test
    fun a_book_being_read_appears_in_the_shelf_and_again_in_the_grid() {
        val store = store()
        // Four books on purpose: they all fit on screen, and an offscreen Lazy item would not be composed at
        // all, which would make a count assertion measure the window size instead of the shelf.
        store.put(Book("b1", "Alpha", "", "content://e/a.epub", lastRead = 3_000))
        store.put(Book("b2", "Bravo", "", "content://e/b.epub", lastRead = 2_000))
        store.put(Book("b3", "Charlie", "", "content://e/c.epub", lastRead = 1_000))
        store.put(Book("b4", "Never Opened", "", "content://e/d.epub", lastRead = 0))
        screen(store)

        compose.onNodeWithText("Continue reading").assertExists()
        // Once above, once in the grid.
        compose.onAllNodesWithText("Alpha").assertCountEquals(2)
        compose.onAllNodesWithText("Charlie").assertCountEquals(2)
        // A book that was never opened belongs in the grid only.
        compose.onAllNodesWithText("Never Opened").assertCountEquals(1)
    }

    @Test
    fun a_library_with_nothing_read_yet_has_no_shelf() {
        val store = store()
        store.put(Book("b1", "Alpha", "", "content://e/a.epub"))
        store.put(Book("b2", "Bravo", "", "content://e/b.epub"))
        screen(store)

        compose.onAllNodesWithText("Continue reading").assertCountEquals(0)
        compose.onAllNodesWithText("Alpha").assertCountEquals(1)
    }
}
