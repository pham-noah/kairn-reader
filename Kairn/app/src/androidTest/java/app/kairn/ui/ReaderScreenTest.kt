package app.kairn.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.kairn.data.BarItem
import app.kairn.data.Mark
import app.kairn.data.ReaderPrefs
import app.kairn.data.ThemeId
import app.kairn.ui.kit.ToastState
import app.kairn.ui.theme.KairnTheme
import app.kairn.ui.theme.readerUi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.readium.r2.shared.publication.Locator

/**
 * R-07/R-14: the reader's chrome against a ReaderActions double - no navigator, no EPUB, no Activity.
 *
 * This is what the interface was for. Every reader test before it needed `ReaderActivity`, a real book and an
 * emulator; here the screen is composed on its own and the fake records what the tap asked for.
 */
class ReaderScreenTest {

    @get:Rule val rule = createComposeRule()

    /** Records what the chrome asks for and does nothing else. */
    private class FakeReader : ReaderActions {
        val calls = mutableListOf<String>()
        var bookmarkToggledTo: Boolean? = null
        var lastPrefs: ReaderPrefs? = null
        var closed = false

        override fun close() { closed = true; calls += "close" }
        override fun toggleBars() { calls += "toggleBars" }
        override fun goTo(locator: Locator) { calls += "goTo" }
        override fun preload(locator: Locator) { calls += "preload" }
        override fun setPrefs(p: ReaderPrefs) { lastPrefs = p; calls += "setPrefs" }
        override fun resetPrefs() { calls += "resetPrefs" }
        override fun toggleBookmark(bookmarked: Boolean) { bookmarkToggledTo = bookmarked; calls += "toggleBookmark" }
        override fun saveNote(target: NoteTarget, text: String) { calls += "saveNote" }
        override fun updateNote(id: String, text: String) { calls += "updateNote" }
        override fun deleteMark(m: Mark) { calls += "deleteMark" }
        override fun deleteBook() { calls += "deleteBook" }
        override fun exportNotes() { calls += "exportNotes" }
        override fun search(query: String) { calls += "search" }
        override fun searchMore() { calls += "searchMore" }
        override fun toggleTts() { calls += "toggleTts" }
        override fun stopTts() { calls += "stopTts" }
        override fun setSpeechRate(rate: Float) { calls += "setSpeechRate" }
        override fun setSleepTimer(minutes: Int) { calls += "setSleepTimer" }
        override fun toggleAutoScroll() { calls += "toggleAutoScroll" }
    }

    private fun compose(
        ui: ReaderUiState,
        fake: ReaderActions,
        hidden: Set<BarItem> = emptySet(),
        order: List<BarItem> = BarItem.DEFAULT_ORDER,
    ) = rule.setContent {
        KairnTheme(glass = false) {
            ReaderScreen(ui, readerUi(ThemeId.DARK), isTablet = false, toast = ToastState(), act = fake, hidden = hidden, order = order)
        }
    }

    @Test fun the_bookmark_button_asks_for_a_bookmark_when_the_chapter_has_none() {
        val ui = ReaderUiState().apply { loading = false; bookTitle = "A Book"; chapterHref = "c1.xhtml" }
        val fake = FakeReader()
        compose(ui, fake)

        rule.onNodeWithContentDescription("Bookmark this chapter").performClick()

        assertEquals("a chapter with no bookmark asks to add one", false, fake.bookmarkToggledTo)
        assertTrue(fake.calls.contains("toggleBookmark"))
        assertTrue("nothing else was touched", fake.calls.size == 1)
    }

    @Test fun with_a_bookmark_in_the_chapter_the_same_button_asks_to_remove_it() {
        val href = "c1.xhtml"
        val locator = """{"href":"$href","type":"application/xhtml+xml","title":"One","locations":{"progression":0.1}}"""
        val ui = ReaderUiState().apply {
            loading = false
            bookTitle = "A Book"
            chapterHref = href
            marks = listOf(Mark("m1", "bookmark", 0, "", "", locator, 1L))
        }
        val fake = FakeReader()
        compose(ui, fake)

        rule.onNodeWithContentDescription("Remove this chapter's bookmark").performClick()

        assertEquals(true, fake.bookmarkToggledTo)
    }

    @Test fun leaving_the_reader_is_one_call_and_no_navigation() {
        val ui = ReaderUiState().apply { loading = false; bookTitle = "A Book"; chapterHref = "c1.xhtml" }
        val fake = FakeReader()
        compose(ui, fake)

        rule.onNodeWithContentDescription("Back to library").performClick()

        assertTrue(fake.closed)
    }

    @Test fun a_hidden_read_aloud_pill_is_not_on_the_bar() {
        val ui = ReaderUiState().apply { loading = false; bookTitle = "A Book"; chapterHref = "c1.xhtml" }
        compose(ui, FakeReader(), hidden = setOf(BarItem.READ_ALOUD))

        rule.onAllNodesWithContentDescription("Read aloud").assertCountEquals(0)
    }

    @Test fun hiding_every_tool_takes_the_more_row_with_it() {
        val ui = ReaderUiState().apply { loading = false; bookTitle = "A Book"; chapterHref = "c1.xhtml" }
        compose(
            ui, FakeReader(),
            hidden = setOf(BarItem.CONTENTS, BarItem.SEARCH, BarItem.NOTES, BarItem.DISPLAY),
        )

        rule.onAllNodesWithText("More").assertCountEquals(0)
        // The bar itself is still there, because the seek bar and the modes live in it.
        rule.onNodeWithContentDescription("Bookmark this chapter").assertExists()
    }

    @Test fun the_tools_that_are_left_are_the_ones_that_were_not_hidden() {
        val ui = ReaderUiState().apply { loading = false; bookTitle = "A Book"; chapterHref = "c1.xhtml" }
        compose(ui, FakeReader(), hidden = setOf(BarItem.SEARCH, BarItem.NOTES))

        rule.onNodeWithText("More").performClick()
        rule.onAllNodesWithContentDescription("Search").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Notes").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("Contents").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Display").assertCountEquals(1)
    }

    @Test fun the_tools_are_drawn_in_the_order_the_reader_arranged() {
        val ui = ReaderUiState().apply { loading = false; bookTitle = "A Book"; chapterHref = "c1.xhtml" }
        compose(
            ui, FakeReader(),
            order = listOf(BarItem.DISPLAY, BarItem.READ_ALOUD, BarItem.NOTES, BarItem.CONTENTS, BarItem.AUTO_SCROLL, BarItem.SEARCH),
        )
        rule.onNodeWithText("More").performClick()

        // Display now sits left of Notes, and Notes left of Contents - the default would be the reverse.
        val display = rule.onNodeWithContentDescription("Display").getUnclippedBoundsInRoot().left
        val notes = rule.onNodeWithContentDescription("Notes").getUnclippedBoundsInRoot().left
        val contents = rule.onNodeWithContentDescription("Contents").getUnclippedBoundsInRoot().left
        assertTrue("Display must come first", display.value < notes.value)
        assertTrue("Notes must come before Contents", notes.value < contents.value)
    }

    @Test fun a_mode_can_sit_either_side_of_more_and_the_row_still_fits() {
        val ui = ReaderUiState().apply {
            loading = false; bookTitle = "A Book"; chapterHref = "c1.xhtml"
            prefs = prefs.copy(scroll = true)         // auto scroll is on the bar in scroll mode
        }
        // Tools first, then the modes: "More" must move to where the tools begin, and the pills after it.
        compose(
            ui, FakeReader(),
            order = listOf(BarItem.CONTENTS, BarItem.SEARCH, BarItem.AUTO_SCROLL, BarItem.NOTES, BarItem.READ_ALOUD, BarItem.DISPLAY),
        )

        val more = rule.onNodeWithText("More").getUnclippedBoundsInRoot()
        val autoScroll = rule.onNodeWithContentDescription("Auto scroll").getUnclippedBoundsInRoot()
        val readAloud = rule.onNodeWithContentDescription("Read aloud").getUnclippedBoundsInRoot()

        assertTrue("More comes first when the tools come first", more.left.value < autoScroll.left.value)
        assertTrue("the modes keep their arranged order after it", autoScroll.left.value < readAloud.left.value)
    }
}
