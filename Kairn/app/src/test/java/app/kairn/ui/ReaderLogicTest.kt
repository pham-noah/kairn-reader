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

    /**
     * A resource the positions scan did not cover the way the content service reads it reports an absurd
     * words-per-position (60,000 words over 120 positions). Extrapolated across the book that is "31 hours
     * left" on a novel, which is exactly the number this band exists to refuse.
     */
    @Test fun an_absurd_ratio_is_refused_instead_of_extrapolated() {
        assertEquals(ReaderLogic.Pace(), ReaderLogic.Pace().add(600_000, 120))
        assertEquals(pace(900, 5), pace(900, 5).add(600_000, 120))
    }

    @Test fun one_position_cannot_describe_a_ratio() {
        assertEquals(ReaderLogic.Pace(), ReaderLogic.Pace().add(400, 1))
    }

    @Test fun the_accumulated_ratio_stays_inside_the_plausible_band() {
        val wide = pace(500_000, 100)                     // 5,000 words a position: past the ceiling
        assertEquals(ReaderLogic.MAX_WORDS_PER_POSITION, wide.wordsPerPosition)
        assertEquals((100 * ReaderLogic.MAX_WORDS_PER_POSITION).toInt() / ReaderLogic.WPM, ReaderLogic.minutesLeft(wide, 100, 0f))
    }

    /** The exact path: no ratio, so no band and no extrapolation - just the book's own word count. */
    @Test fun minutes_left_from_the_books_own_word_count() {
        assertNull(ReaderLogic.minutesLeftInBook(0, 0.5f))
        assertEquals(0, ReaderLogic.minutesLeftInBook(120_000, 1f))
        assertEquals(250, ReaderLogic.minutesLeftInBook(120_000, 0.5f))   // 60,000 words at 240 wpm
        assertEquals(500, ReaderLogic.minutesLeftInBook(120_000, 0f))
        assertEquals(0, ReaderLogic.minutesLeftInBook(120_000, 1.7f))     // clamped
    }

    /** The counter works on raw XHTML bytes: markup must not be counted, text must be. */
    @Test fun words_are_counted_outside_tags_only() {
        val xhtml = """<?xml version="1.0"?><p class="lead">Hello brave   new world</p><span title="a b c">again</span>"""
        assertEquals(5, ReaderLogic.countWords(xhtml.toByteArray()))       // hello brave new world again
        assertEquals(0, ReaderLogic.countWords("<p></p>".toByteArray()))
        assertEquals(0, ReaderLogic.countWords(ByteArray(0)))
    }

    @Test fun punctuation_entities_and_multibyte_text_do_not_split_words() {
        assertEquals(3, ReaderLogic.countWords("Tom &amp; Jerry".toByteArray()))
        assertEquals(2, ReaderLogic.countWords("naïve café".toByteArray()))   // 2-byte chars stay one word
    }

    /** TTS input: sentences packed under the engine's per-utterance cap, nothing lost. */
    @Test fun tts_chunks_keep_every_word_and_stay_under_the_cap() {
        val text = (1..400).joinToString(" ") { "sentence number $it ends here." }
        val chunks = ReaderLogic.ttsChunks(text)
        assertTrue("expected more than one chunk", chunks.size > 1)
        assertTrue("a chunk is too long", chunks.all { it.length <= ReaderLogic.TTS_CHUNK })
        val joined = chunks.joinToString(" ")
        assertEquals(400, Regex("number").findAll(joined).count())
    }

    @Test fun one_monstrous_sentence_is_split_rather_than_dropped() {
        val text = "x".repeat(ReaderLogic.TTS_CHUNK * 2 + 5)
        val chunks = ReaderLogic.ttsChunks(text)
        assertTrue(chunks.all { it.length <= ReaderLogic.TTS_CHUNK })
        assertEquals(text.length, chunks.sumOf { it.length })
    }

    @Test fun an_empty_chapter_produces_no_speech() {
        assertTrue(ReaderLogic.ttsChunks("   \n  ").isEmpty())
    }

    /** Play from where the reader is: the offset lands just after a sentence, never mid-word. */
    @Test fun tts_starts_at_the_readers_position_snapped_to_a_sentence() {
        val text = "First sentence here. Second sentence here. Third sentence here."
        assertEquals(0, ReaderLogic.ttsStartOffset(text, 0f))
        val at = ReaderLogic.ttsStartOffset(text, 0.45f)          // inside the second sentence
        assertTrue("landed mid-word: ${text.substring(at)}", text.substring(at).startsWith("Third") ||
            text.substring(at).startsWith("Second"))
        assertEquals(0, ReaderLogic.ttsStartOffset("", 0.5f))
    }
}
