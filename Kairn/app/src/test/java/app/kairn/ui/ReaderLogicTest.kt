package app.kairn.ui

import app.kairn.data.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the reader arithmetic: no device, no Readium. */
class ReaderLogicTest {

    private fun pace(words: Long, positions: Int) = ReaderLogic.Pace(words, positions)

    @Test fun no_estimate_until_the_pace_and_positions_are_known() {
        assertNull(ReaderLogic.minutesLeft(pace(0, 0), 100, 0.2f))
        assertNull(ReaderLogic.minutesLeft(pace(900, 0), 100, 0.2f))
        assertNull(ReaderLogic.minutesLeft(pace(900, 5), 0, 0.2f))
    }

    @Test fun a_tenth_of_the_way_through_is_a_tenth_of_the_words_left() {
        // 900 words across 5 positions = 180 words each; 100 positions in the book = 18,000 words total.
        // At 10% read, 16,200 words remain: 67.5 minutes at 240 wpm.
        assertEquals(67, ReaderLogic.minutesLeft(pace(900, 5), 100, 0.1f))
    }

    @Test fun progress_is_clamped_so_a_bad_value_cannot_go_negative() {
        assertEquals(0, ReaderLogic.minutesLeft(pace(900, 5), 100, 1.7f))
        assertEquals(75, ReaderLogic.minutesLeft(pace(900, 5), 100, -3f))   // clamped to 0: the whole book is left
    }

    /**
     * A title page is 20 words across one position: deriving the pace from the chapter on screen moved
     * "min left" by an hour on a single tap, so a small chapter must not count.
     */
    @Test fun a_title_page_is_ignored_instead_of_swinging_the_estimate() {
        assertEquals(ReaderLogic.Pace(), ReaderLogic.Pace().add(20, 1))
        assertEquals(pace(900, 5), pace(900, 5).add(20, 1))
    }

    @Test fun a_second_real_chapter_moves_the_estimate_smoothly() {
        val both = ReaderLogic.Pace().add(900, 5).add(850, 5)
        assertEquals(pace(1750, 10), both)
        // 175 words per position over 100 positions at 0% read: 17,500 words, 72 minutes at 240 wpm.
        assertEquals(72, ReaderLogic.minutesLeft(both, 100, 0f))
    }

    @Test fun deleting_removes_only_the_named_mark() {
        val a = Mark("a", "highlight", 1, "q", "", "{}", 1)
        val b = Mark("b", "note", 2, "q", "n", "{}", 2)
        assertEquals(listOf(b), ReaderLogic.withoutMark(listOf(a, b), "a"))
        assertEquals(listOf(a, b), ReaderLogic.withoutMark(listOf(a, b), "missing"))
    }

    @Test fun a_chapter_has_a_bookmark_only_when_one_sits_in_it() {
        fun hrefOf(m: Mark) = m.locator
        val inChapter = Mark("1", "bookmark", 1, "", "", "ch2", 0)
        val elsewhere = Mark("2", "bookmark", 1, "", "", "ch1", 0)
        val highlightHere = Mark("3", "highlight", 1, "q", "", "ch2", 0)
        assertTrue(ReaderLogic.chapterHasBookmark(listOf(elsewhere, inChapter), "ch2", ::hrefOf))
        assertFalse(ReaderLogic.chapterHasBookmark(listOf(elsewhere, highlightHere), "ch2", ::hrefOf))
    }
}
