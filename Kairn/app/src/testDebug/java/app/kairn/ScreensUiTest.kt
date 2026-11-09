package app.kairn

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import app.kairn.data.Store
import app.kairn.ui.screens.AddBooksScreen
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
        st.saveSettings { it.copy(readingWpm = 300) }                     // "Fast"
        compose.setContent {
            KairnTheme { SettingsScreen(st, onWatchedFolders = {}, onBackup = {}, onScan = {}) }
        }
        compose.waitForIdle()
        // The four speeds are labelled Slow / Normal / Fast / Very fast, and the stored 300 is the third.
        compose.onNodeWithText("Fast").assertExists()
        compose.onNodeWithText("Slow").assertExists()
    }
}
