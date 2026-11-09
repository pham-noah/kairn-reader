package app.kairn

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.Book
import app.kairn.data.Store
import app.kairn.ui.screens.LibraryScreen
import app.kairn.ui.theme.KairnTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * The UI, drawn for real on the JVM: a Robolectric composition with the app's own theme, strings and Store.
 * This is not a substitute for a phone (nothing is rendered to pixels that a human checks), but a screen that
 * throws while it lays out fails here instead of on the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LibraryScreenUiTest {

    @get:Rule val compose = createComposeRule()

    private fun newStore(): Store {
        val base = Files.createTempDirectory("kairn-ui").toFile()
        return Store.forTesting(base)
    }

    private fun draw(store: Store, onOpen: (Book) -> Unit = {}) {
        compose.setContent {
            KairnTheme {
                LibraryScreen(
                    store = store, shelf = null, onShelf = {},
                    onOpen = onOpen, onAdd = {}, onPickFolder = {}, onPickBook = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun the_empty_library_draws_and_offers_something_to_tap() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = newStore()
        runBlocking { store.load() }
        draw(store)
        compose.onRoot().assertExists()
        val clickables = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertTrue("nothing to tap on the empty library", clickables > 0)
        assertTrue(ctx.getString(app.kairn.R.string.s_add_books).isNotBlank())
    }

    @Test fun a_library_of_three_books_draws_its_titles() {
        val store = newStore()
        store.put(Book(id = "a1", title = "The Left Hand of Darkness", author = "Le Guin", uri = "file:///a.epub"))
        store.put(Book(id = "a2", title = "Piranesi", author = "Clarke", uri = "file:///b.epub", progress = 0.4f))
        store.put(Book(id = "a3", title = "It", author = "King", uri = "file:///c.epub", missing = true))
        runBlocking { store.load() }
        draw(store)
        compose.onNodeWithText("The Left Hand of Darkness").assertExists()
        compose.onNodeWithText("Piranesi").assertExists()
    }

    @Test fun a_book_card_opens_that_book() {
        val store = newStore()
        store.put(Book(id = "z9", title = "Piranesi", author = "Clarke", uri = "file:///b.epub"))
        runBlocking { store.load() }
        var opened: Book? = null
        draw(store) { opened = it }
        compose.onNodeWithText("Piranesi").performClick()
        compose.waitForIdle()
        assertTrue("the card did not open the book", opened?.id == "z9")
    }
}
