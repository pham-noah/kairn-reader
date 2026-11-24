package app.kairn

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import app.kairn.data.Book
import app.kairn.data.Store
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import app.kairn.ui.screens.AddBooksScreen
import app.kairn.ui.screens.LibraryScreen
import app.kairn.ui.screens.SettingsScreen
import app.kairn.ui.theme.KairnTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/** The two screens a reader touches before there is a book open, drawn for real on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ScreensUiTest {

    @get:Rule val compose = createComposeRule()

    private fun store(): Store {
        val st = Store.forTesting(Files.createTempDirectory("kairn-ui2").toFile())
        runBlocking { st.load() }
        return st
    }

    @Test fun settings_draws_its_groups_and_controls() {
        val st = store()
        compose.setContent {
            KairnTheme {
                SettingsScreen(st, onWatchedFolders = {}, onBackup = {}, onScan = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
        assertTrue(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size > 0)
    }

    @Test fun add_books_draws_the_transfer_and_folder_options() {
        val st = store()
        compose.setContent {
            KairnTheme {
                AddBooksScreen(st, onBack = {}, onPickFolder = {}, onPickBook = {}, onImportFile = { _, _ -> })
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
        assertTrue(compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size > 0)
    }

    @Test fun the_reading_speed_control_shows_the_stored_value() {
        val st = store()
        st.saveSettings { it.copy(readingWpm = 300) }
        compose.setContent {
            KairnTheme { SettingsScreen(st, onWatchedFolders = {}, onBackup = {}, onScan = {}) }
        }
        compose.waitForIdle()
        // The control is a slider now, and the number it writes is the number it shows: 300 reads as "300 wpm"
        // rather than as the word "Fast", which told nobody what it was going to save.
        compose.onNodeWithText("Reading speed").assertExists()
        compose.onNodeWithText("300 wpm").assertExists()
    }

    /**
     * The tab host disposes a tab when another one is up, and that used to take the search with it: type a
     * query, glance at Notes, come back, and the box was empty and the grid back at the top. The state is
     * saved per tab now, so the query survives the round trip.
     */
    @Test fun the_library_keeps_its_search_while_another_tab_is_up() {
        val st = store()
        // One book, so the screen draws its grid (and the header's search) instead of the empty-library state.
        st.put(Book("b1", "Silver", "", "file:///silver.epub"))
        compose.setContent {
            KairnTheme {
                var library by remember { mutableStateOf(true) }
                val keep = rememberSaveableStateHolder()
                Column(Modifier.fillMaxSize()) {
                    TextButton({ library = !library }) { Text("swap") }
                    Box(Modifier.fillMaxSize()) {
                        if (library) {
                            keep.SaveableStateProvider("library") {
                                LibraryScreen(st, null, {}, {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Search library").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("silver")
        compose.onNodeWithText("silver").assertExists()

        compose.onNodeWithText("swap").performClick()      // the tab leaves the composition
        compose.onNodeWithText("swap").performClick()      // and comes back
        compose.waitForIdle()

        assertTrue(
            "the query was lost when the tab was disposed",
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
                && runCatching { compose.onNodeWithText("silver").assertExists() }.isSuccess,
        )
    }
}
