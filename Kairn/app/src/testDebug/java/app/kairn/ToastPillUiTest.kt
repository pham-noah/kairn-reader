package app.kairn

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.kairn.data.ReaderPrefs
import app.kairn.ui.ReaderActions
import app.kairn.ui.ReaderScreen
import app.kairn.ui.ReaderUiState
import app.kairn.ui.kit.ToastState
import app.kairn.ui.theme.KairnTheme
import app.kairn.ui.theme.readerUi
import app.kairn.data.ThemeId
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.Url
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The toast pill in the reader, as a reader reports it: after a bookmark it said its piece and then
 * would not leave — a swipe on it paged the chapter instead of dismissing it, and it kept a fixed
 * 200dp offset so it hung mid-text once the bars were hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ToastPillUiTest {

    @get:Rule val compose = createComposeRule()

    private val toast = ToastState()
    private lateinit var ui: ReaderUiState

    private class NoActions : ReaderActions {
        override fun close() {}
        override fun toggleBars() {}
        override fun goTo(locator: org.readium.r2.shared.publication.Locator) {}
        override fun preload(locator: org.readium.r2.shared.publication.Locator) {}
        override fun setPrefs(p: ReaderPrefs) {}
        override fun resetPrefs() {}
        override fun toggleBookmark(bookmarked: Boolean) {}
        override fun saveNote(target: app.kairn.ui.NoteTarget, text: String) {}
        override fun updateNote(id: String, text: String) {}
        override fun deleteMark(m: app.kairn.data.Mark) {}
        override fun deleteBook() {}
        override fun exportNotes() {}
        override fun search(query: String) {}
        override fun toggleTts() {}
        override fun stopTts() {}
        override fun setCastEnabled(on: Boolean) {}
        override fun setCastVoice(speaker: String, slot: Int) {}
        override fun setSpeechRate(rate: Float) {}
        override fun setSleepTimer(minutes: Int) {}
        override fun toggleAutoScroll() {}
        override fun searchMore() {}
    }

    private fun state(bars: Boolean): ReaderUiState {
        val s = ReaderUiState()
        ui = s
        s.loading = false
        s.bookTitle = "Piranesi"
        s.chapterTitle = "Chapter Three"
        s.bookProgress = 0.42f
        s.chapterProgress = 0.5f
        s.bookWords = 60_000
        s.chapterWords = 3_000
        s.prefs = ReaderPrefs()
        s.bars = bars
        return s
    }

    /** Somewhere to come back to; the screen only uses it as a destination and a key. */
    private val anywhere = Locator(
        href = Url("OEBPS/c1.xhtml")!!,
        mediaType = MediaType.XHTML,
        title = "Chapter One",
        locations = Locator.Locations(),
        text = Locator.Text(),
    )

    /** The pill itself, for a gesture or a measurement. It must be the only one on screen. */
    private fun pill() = compose.onNodeWithText("Bookmark added")

    /** How many pills say it: fetching from the collection is the only public "is it still there". */
    private fun pills() = compose.onAllNodesWithText("Bookmark added").fetchSemanticsNodes().size

    private fun draw(bars: Boolean) {
        compose.setContent {
            KairnTheme {
                ReaderScreen(ui = state(bars), colors = readerUi(ThemeId.DARK), isTablet = false, toast = toast, act = NoActions(), wpm = 240)
            }
        }
        compose.waitForIdle()
    }

    private fun show() {
        compose.runOnIdle { toast.show("Bookmark added") }
        compose.waitForIdle()
    }

    /** The bottom of the pill, in dp from the top of the 780dp screen (xxhdpi = 3x). */
    private fun pillBottomDp(): Float = pill().fetchSemanticsNode().boundsInRoot.bottom / 3f

    @Test fun the_pill_is_drawn_at_all() {
        draw(bars = true)
        show()
        pill().assertExists()
    }

    @Test fun the_pill_leaves_after_its_moment() {
        draw(bars = true)
        show()
        compose.mainClock.advanceTimeBy(3_400)        // past its 2600 ms life
        compose.waitForIdle()
        assertTrue("the pill was still on screen after its timer", pills() == 0)
    }

    @Test fun a_swipe_on_the_pill_dismisses_it() {
        draw(bars = true)
        show()
        pill().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue("a swipe left the pill on screen", pills() == 0)
    }

    @Test fun the_pill_clears_the_bottom_bar_while_the_bars_are_up() {
        draw(bars = true)
        show()
        val bottom = pillBottomDp()
        assertTrue("the pill sits at ${bottom}dp with the bar up", bottom in 500f..660f)
    }

    @Test fun the_footnote_card_clears_the_bottom_bar() {
        draw(bars = true)
        compose.runOnIdle { ui.footnote = "A note from the page." }
        compose.waitForIdle()
        val barTop = ui.chromeTopPx / 3f
        // The card's own bottom edge, not just its last child: the card's 16dp of padding below Close was
        // the part that sat behind the bar at the old fixed 150dp.
        val cardBottom = compose.onNodeWithText("Close").fetchSemanticsNode().boundsInRoot.bottom / 3f + 16f
        assertTrue("the card ends at ${cardBottom}dp and the bar starts at ${barTop}dp", cardBottom <= barTop)
    }

    @Test fun the_pill_stacks_above_the_footnote_card() {
        draw(bars = true)
        compose.runOnIdle { ui.footnote = "A note from the page."; toast.show("Bookmark added") }
        compose.waitForIdle()
        val cardTop = compose.onNodeWithText("Footnote").fetchSemanticsNode().boundsInRoot.top / 3f
        val pillBottom = pillBottomDp()
        assertTrue("the pill ends at ${pillBottom}dp and the card starts at ${cardTop}dp", pillBottom <= cardTop)
    }

    @Test fun the_top_bar_reports_where_the_page_begins() {
        draw(bars = true)
        val barBottom = ui.topChromeBottomPx / 3f
        assertTrue("the top bar reported ${barBottom}dp", barBottom in 40f..120f)
    }

    @Test fun with_the_bars_hidden_nothing_is_excluded_from_the_page() {
        draw(bars = false)
        assertTrue("a hidden top bar still claimed to ${ui.topChromeBottomPx}", ui.topChromeBottomPx == 0f)
        assertTrue("a hidden bottom bar still claimed to start at ${ui.chromeTopPx}", ui.chromeTopPx == Float.MAX_VALUE)
    }

    /** The jump-back pill, which now lives in the bottom stack rather than over the page. */
    private fun jumpBackBounds() = compose.onNodeWithText("Back to where you were").fetchSemanticsNode().boundsInRoot

    @Test fun the_jump_back_pill_sits_at_the_bottom_not_over_the_page() {
        // It used to be pinned under the top bar, covering the first lines of the page it offered to return to.
        draw(bars = true)
        compose.runOnIdle { ui.jumpBack = anywhere }
        compose.waitForIdle()
        val pill = jumpBackBounds()
        val barTop = ui.chromeTopPx / 3f
        assertTrue("the pill starts at ${pill.top / 3f}dp, in the page", pill.top / 3f > 500f)
        assertTrue("the pill ends at ${pill.bottom / 3f}dp, below the bar at ${barTop}dp", pill.bottom / 3f <= barTop)
    }

    @Test fun the_jump_back_pill_sits_near_the_corner_when_the_bars_are_hidden() {
        draw(bars = false)
        compose.runOnIdle { ui.jumpBack = anywhere }
        compose.waitForIdle()
        val pill = jumpBackBounds()
        assertTrue("the pill ends at ${pill.bottom / 3f}dp with the bars hidden", pill.bottom / 3f > 700f)
    }

    @Test fun the_jump_back_pill_stacks_above_the_toast() {
        // Both are transient and both live at the bottom edge now: the action sits above the message.
        draw(bars = true)
        compose.runOnIdle { ui.jumpBack = anywhere; toast.show("Bookmark added") }
        compose.waitForIdle()
        val pillBottom = jumpBackBounds().bottom / 3f
        val toastTop = pill().fetchSemanticsNode().boundsInRoot.top / 3f
        assertTrue("the pill ends at ${pillBottom}dp and the toast starts at ${toastTop}dp", pillBottom <= toastTop)
    }

    @Test fun the_pill_drops_to_the_corner_when_the_bars_are_hidden() {
        draw(bars = false)
        show()
        val bottom = pillBottomDp()
        assertTrue("the pill sits at ${bottom}dp once the bars are hidden", bottom > 700f)
    }
}
