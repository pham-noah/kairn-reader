package app.kairn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribution is the part of a full-cast read-aloud that can be wrong in public: a line given to the wrong
 * character sounds like a bug. These tests state the rule the engine relies on - a name beside the
 * attribution verb, or the narrator - and pin the cases that decide whether it is worth shipping at all.
 */
class SpeechCastTest {

    private fun speakerOf(text: String, quote: String): String {
        val spans = SpeechCast.attribute(text)
        val at = text.indexOf(quote)
        assertTrue("quote not in sample: $quote", at >= 0)
        return SpeechCast.speakerAt(spans, at + 1)
    }

    @Test fun a_chapter_without_dialogue_is_one_narrator_span() {
        val text = "The train was late. Nobody on the platform said a word about it."
        val spans = SpeechCast.attribute(text)
        assertEquals(1, spans.size)
        assertEquals("", spans[0].speaker)
        assertEquals(0, spans[0].start)
        assertEquals(text.length, spans[0].end)
        assertTrue(SpeechCast.speakers(spans).isEmpty())
    }

    @Test fun the_name_after_the_verb_is_the_speaker() {
        val text = "\"Come with me,\" said Frodo, and he turned for the door."
        assertEquals("Frodo", speakerOf(text, "Come with me"))
    }

    @Test fun the_name_before_the_verb_is_the_speaker_too() {
        val text = "Sam said, \"I will carry it, if you hold the light.\""
        assertEquals("Sam", speakerOf(text, "I will carry it"))
    }

    @Test fun a_pronoun_is_never_a_character() {
        // "he said" cannot name anyone: the line stays with the narrator rather than inventing a "He".
        val text = "\"Run,\" he said, and that was all."
        assertEquals("", speakerOf(text, "Run"))
    }

    @Test fun an_article_or_a_sentence_opening_is_never_a_character() {
        val text = "\"No.\" The door closed behind them and nobody spoke again."
        assertEquals("", speakerOf(text, "No"))
    }

    @Test fun curly_quotes_are_dialogue_too() {
        val text = "“You are late,” said Galadriel, without turning round."
        assertEquals("Galadriel", speakerOf(text, "You are late"))
    }

    @Test fun an_unattributed_line_between_two_known_voices_alternates() {
        val text = "\"First,\" said Frodo. \"Second,\" said Sam. \"Third,\" in a whisper."
        val spans = SpeechCast.attribute(text)
        val third = text.indexOf("Third")
        // Frodo, Sam, and then the other of the two - not a new speaker and not the last one again.
        assertEquals("Frodo", SpeechCast.speakerAt(spans, text.indexOf("First") + 1))
        assertEquals("Sam", SpeechCast.speakerAt(spans, text.indexOf("Second") + 1))
        assertEquals("Frodo", SpeechCast.speakerAt(spans, third + 1))
    }

    @Test fun a_cast_never_grows_past_the_cap() {
        val names = listOf("Ada", "Bram", "Cyrus", "Dora", "Emil", "Faye", "Gwen", "Hugo", "Iris", "Jonas")
        val text = names.joinToString(" ") { "\"Line.\" said $it." }
        val speakers = SpeechCast.speakers(SpeechCast.attribute(text))
        assertTrue("got $speakers", speakers.size <= SpeechCast.MAX_SPEAKERS)
    }

    @Test fun spans_cover_the_text_exactly_once_and_in_order() {
        val text = "Narrator opens. \"One,\" said Ada. More narration here. \"Two,\" said Bram. End."
        val spans = SpeechCast.attribute(text)
        var cursor = 0
        for (s in spans) {
            assertEquals("gap or overlap at ${s.start}", cursor, s.start)
            assertTrue(s.end > s.start)
            cursor = s.end
        }
        assertEquals(text.length, cursor)
        // No two adjacent spans carry the same speaker: the merge is part of the contract.
        spans.zipWithNext().forEach { (a, b) -> assertTrue("unmerged ${a.speaker}", a.speaker != b.speaker) }
    }

    @Test fun an_empty_text_has_no_spans_and_no_crash() {
        assertTrue(SpeechCast.attribute("").isEmpty())
        assertEquals("", SpeechCast.speakerAt(emptyList(), 4))
    }

    @Test fun a_speaker_key_is_the_name_without_its_punctuation() {
        val spans = SpeechCast.attribute("\"Halt!\" cried Boromir. \"Again!\" said Boromir.")
        val cast = SpeechCast.speakers(spans)
        assertEquals(listOf("Boromir"), cast)
        assertNotNull(cast.firstOrNull())
    }
}
