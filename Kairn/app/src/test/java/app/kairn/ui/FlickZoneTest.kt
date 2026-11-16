package app.kairn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a chapter swipe may start. Both bars and the toast pill are Compose content in the reader's own
 * window, and the Activity's gate swallows the ACTION_UP when it takes a flick — so a drag that began on one
 * of them paged the chapter and starved the control of the gesture it was waiting for.
 */
class FlickZoneTest {

    // A 780dp-tall window at 3x: the top bar ends at 198px, the bottom bar starts at 1770px.
    private val topBar = 198f
    private val bottomBar = 1770f
    private val noPill = -1f      // the pill's band when it is not on screen

    @Test fun a_flick_on_the_page_pages() {
        assertTrue(ReaderLogic.flickPagesFrom(900f, topBar, bottomBar, noPill, noPill))
    }

    @Test fun a_flick_on_the_top_bar_does_not() {
        // The bookmark button lives in this band: dragging on it used to turn the chapter under the finger.
        assertFalse(ReaderLogic.flickPagesFrom(60f, topBar, bottomBar, noPill, noPill))
        assertFalse(ReaderLogic.flickPagesFrom(197f, topBar, bottomBar, noPill, noPill))
    }

    @Test fun a_flick_on_the_bottom_bar_does_not() {
        assertFalse(ReaderLogic.flickPagesFrom(1770f, topBar, bottomBar, noPill, noPill))
        assertFalse(ReaderLogic.flickPagesFrom(2300f, topBar, bottomBar, noPill, noPill))
    }

    @Test fun a_flick_on_the_pill_does_not() {
        // The pill measures 560..580dp: right above the bar, which starts at 590dp.
        assertFalse(ReaderLogic.flickPagesFrom(1700f, topBar, bottomBar, 1680f, 1740f))
        // the page just above it, and the 10dp of gap under it, still page
        assertTrue(ReaderLogic.flickPagesFrom(1600f, topBar, bottomBar, 1680f, 1740f))
        assertTrue(ReaderLogic.flickPagesFrom(1755f, topBar, bottomBar, 1680f, 1740f))
    }

    @Test fun with_the_bars_hidden_the_whole_window_pages() {
        assertTrue(ReaderLogic.flickPagesFrom(10f, 0f, Float.MAX_VALUE, noPill, noPill))
        assertTrue(ReaderLogic.flickPagesFrom(2330f, 0f, Float.MAX_VALUE, noPill, noPill))
    }

    @Test fun bars_and_a_pill_that_are_gone_protect_nothing() {
        // Both bars out of composition (0 and MAX_VALUE) and no pill measured: nothing is excluded.
        assertTrue(ReaderLogic.flickPagesFrom(0f, 0f, Float.MAX_VALUE, noPill, noPill))
        assertTrue(ReaderLogic.flickPagesFrom(900f, 0f, Float.MAX_VALUE, 0f, 0f))
    }
}
