package app.kairn

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import app.kairn.data.Mark
import app.kairn.data.ReaderPrefs
import app.kairn.ui.ReaderActions
import app.kairn.ui.ReaderScreen
import app.kairn.ui.ReaderUiState
import app.kairn.ui.kit.ToastState
import app.kairn.ui.theme.KairnTheme
import app.kairn.ui.theme.readerUi
import app.kairn.data.ThemeId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator

/** The reader's own chrome, drawn on the JVM: the seek bar, the top bar, the bottom bar and the sheets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ReaderScreenUiTest {

    @get:Rule val compose = createComposeRule()

    private class NoActions : ReaderActions {
        var bars = 0
        override fun close() {}
        override fun toggleBars() { bars++ }
        override fun goTo(locator: Locator) {}
        override fun preload(locator: Locator) {}
        override fun setPrefs(p: ReaderPrefs) {}
        override fun resetPrefs() {}
        override fun toggleBookmark(bookmarked: Boolean) {}
        override fun saveNote(target: app.kairn.ui.NoteTarget, text: String) {}
        override fun updateNote(id: String, text: String) {}
        override fun deleteMark(m: Mark) {}
        override fun deleteBook() {}
        override fun exportNotes() {}
        override fun search(query: String) {}
        override fun toggleTts() {}
        override fun stopTts() {}
        override fun setSpeechRate(rate: Float) {}
        override fun setSleepTimer(minutes: Int) {}
        override fun toggleAutoScroll() {}
        override fun searchMore() {}
    }

    private fun state(block: ReaderUiState.() -> Unit = {}): ReaderUiState {
        val s = ReaderUiState()
        s.loading = false
        s.bookTitle = "Piranesi"
        s.chapterTitle = "Chapter Three"
        s.bookProgress = 0.42f
        s.chapterProgress = 0.5f
        s.bookWords = 60_000
        s.chapterWords = 3_000
        s.pace = app.kairn.ui.ReaderLogic.Pace(900, 5)
        s.prefs = ReaderPrefs()
        s.apply(block)
        return s
    }

    private fun draw(s: ReaderUiState, act: ReaderActions = NoActions()) {
        compose.setContent {
            KairnTheme {
                ReaderScreen(ui = s, colors = readerUi(ThemeId.DARK), isTablet = false, toast = ToastState(), act = act, wpm = 240)
            }
        }
        compose.waitForIdle()
    }

    @Test fun the_reading_page_draws_with_its_chrome() {
        draw(state())
        compose.onRoot().assertExists()
        compose.onNodeWithText("Piranesi").assertExists()
        assertTrue("the reader drew nothing to tap", compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun a_book_with_no_word_count_yet_still_draws() {
        draw(state { bookWords = 0; chapterWords = 0; pace = app.kairn.ui.ReaderLogic.Pace() })
        compose.onRoot().assertExists()
    }

    @Test fun a_page_that_failed_to_open_draws_the_cant_open_sheet() {
        draw(state { error = "FileNotFoundException: content not found" })
        compose.onNodeWithText("This book can't be opened").assertExists()
        compose.onNodeWithText("The file may be damaged or too large. The rest of your data is safe.").assertExists()
        // The sheet's own text is generic: the specific failure in ui.error is never shown to the reader.
    }

    @Test fun the_loading_cover_is_drawn_before_the_first_locator() {
        draw(state { loading = true })
        compose.onRoot().assertExists()
    }

    @Test fun scroll_mode_draws() {
        draw(state { prefs = ReaderPrefs(scroll = true) })
        compose.onRoot().assertExists()
    }

    @Test fun two_page_mode_draws() {
        draw(state { prefs = ReaderPrefs(twoPage = true) })
        compose.onRoot().assertExists()
    }

    @Test fun a_zero_reading_speed_does_not_take_the_page_down() {
        // The setting only offers 180..360, but a foreign settings.json could hold 0; the page must still draw.
        compose.setContent {
            KairnTheme {
                ReaderScreen(ui = state(), colors = readerUi(ThemeId.DARK), isTablet = false, toast = ToastState(), act = NoActions(), wpm = 0)
            }
        }
        compose.waitForIdle()
        compose.onRoot().assertExists()
    }
}
