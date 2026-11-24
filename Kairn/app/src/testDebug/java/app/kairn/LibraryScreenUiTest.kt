package app.kairn

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider
import app.kairn.data.Book
import app.kairn.data.Store
import app.kairn.data.Status
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

    /**
     * The reported defect, as a number rather than a picture: Want, Reading and Finished each with a count used to
     * come to 390dp on a 411dp phone and drop Finished onto a second row. Three cells share one line if their tops
     * are the same line - so the assertion is the y of each label, not a screenshot somebody has to squint at.
     */
    @Test fun the_three_status_chips_share_one_row() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = newStore()
        store.put(Book(id = "r1", title = "Piranesi", author = "Clarke", uri = "file:///b.epub", status = Status.READING))
        runBlocking { store.load() }
        draw(store)
        val tops = listOf(R.string.s_want, R.string.s_reading, R.string.s_finished).map {
            compose.onNodeWithText(ctx.getString(it)).getBoundsInRoot().top.value
        }
        val spread = tops.max() - tops.min()
        assertTrue("chips stacked on different rows: $tops", spread < 1f)
    }
    /**
     * The press that changes the shelf is the one that buzzes. Robolectric hands out a fresh `SystemVibrator` for
     * every `getSystemService` call, so the vibration cannot be asserted from outside the composition (each object
     * carries its own shadow state) - what is asserted here is that the press reaches the chip's handler at all,
     * which is the same lambda that calls `haptics.tick()`; `HapticsTest` asserts that tick reaches the vibrator.
     * The two together are the chain, and neither is a screenshot.
     */
    @Test fun tapping_a_status_chip_filters_the_shelf() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = newStore()
        store.put(Book(id = "r3", title = "Solenoid", author = "Cartarescu", uri = "file:///s.epub", status = Status.READING))
        runBlocking { store.load() }
        draw(store)
        compose.onNodeWithText(ctx.getString(app.kairn.R.string.s_want)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Solenoid").assertDoesNotExist()
    }
}
