package app.kairn.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kairn.data.Book
import app.kairn.data.Status
import app.kairn.data.Store
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The library is the front door and its filters are derived state, so they get exercised rather than read.
 * A real Store on a temp directory keeps it honest: the screens observe the same StateFlows production does.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store(): Store {
        val store = Store.forTesting(File(ctx.cacheDir, "lib-${System.nanoTime()}"))
        runBlocking { store.load() }          // the load gate: without it the screen shows the placeholder
        return store
    }

    private fun screen(store: Store) = compose.setContent {
        LibraryScreen(
            store = store, shelf = null, onShelf = {},
            onOpen = {}, onAdd = {}, onPickFolder = {}, onPickBook = {},
        )
    }

    @Test
    fun an_empty_library_says_so_instead_of_drawing_a_blank_grid() {
        screen(store())
        compose.onNodeWithText("No books yet").assertIsDisplayed()
    }

    @Test
    fun a_book_in_the_store_reaches_the_grid_with_its_author() {
        val store = store()
        store.put(Book("b1", "Moby Dick", "Herman Melville", "content://example/moby.epub"))
        screen(store)
        compose.onNodeWithText("Moby Dick").assertIsDisplayed()
        compose.onNodeWithText("Herman Melville").assertIsDisplayed()
    }

    @Test
    fun the_status_filter_keeps_only_the_matching_books() {
        val store = store()
        store.put(Book("r1", "Reading Book", "", "content://example/r.epub", status = Status.READING))
        store.put(Book("f1", "Finished Book", "", "content://example/f.epub", status = Status.FINISHED))
        screen(store)
        compose.onNodeWithText("Reading Book").assertIsDisplayed()
        compose.onNodeWithText("Finished Book").assertIsDisplayed()

        compose.onNodeWithText("Finished", substring = false).performClick()   // the filter chip

        compose.onNodeWithText("Finished Book").assertIsDisplayed()
        compose.onNodeWithText("Reading Book").assertDoesNotExist()
    }

    @Test
    fun removing_a_book_asks_the_store_and_the_grid_follows() {
        val store = store()
        store.put(Book("x1", "Gone Soon", "", "content://example/x.epub"))
        screen(store)
        compose.onNodeWithText("Gone Soon").assertIsDisplayed()
        store.remove("x1")
        compose.waitForIdle()
        compose.onNodeWithText("Gone Soon").assertDoesNotExist()
    }

    @Test
    fun the_selection_bar_can_finish_the_books_you_picked() {
        val store = store()
        store.put(Book("s1", "Pick Me", "", "content://example/s.epub"))
        screen(store)
        compose.onNodeWithText("Pick Me").performTouchInput { longClick() }        // opens the action sheet
        compose.onNodeWithText("Select several books").performClick()               // into selection mode with it picked
        compose.onNodeWithContentDescription("Mark as finished").performClick()
        compose.waitForIdle()
        assertEquals(Status.FINISHED, store.get("s1")?.status)
    }
}

