package app.kairn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between attribution and the engine: a piece is one utterance, and one utterance can only carry
 * one voice. So a change of speaker must break the piece - and the offsets must still point into the
 * chapter, because that is what page-follow and resume are built on.
 */
class ReaderCastTest {

    @Test fun a_quote_becomes_its_own_utterance() {
        val text = "The hall was quiet. \"Where is he?\" asked Ada. Nobody answered her."
        val pieces = ReaderLogic.ttsPieces(text, SpeechCast.attribute(text))
        assertEquals(listOf("", "Ada", ""), pieces.map { it.speaker })
        assertEquals("Where is he?", pieces[1].text.trim(' ', '"'))
    }

    @Test fun two_speakers_in_a_row_are_never_one_utterance() {
        val text = "\"Yes,\" said Ada. \"No,\" said Bram."
        val pieces = ReaderLogic.ttsPieces(text, SpeechCast.attribute(text))
        val speakers = pieces.map { it.speaker }
        // Ada's line, Bram's line, and the two narration tags between them - each in its own utterance.
        assertTrue("one piece for two voices: $pieces", "Ada" in speakers && "Bram" in speakers)
        assertTrue(pieces.size >= 4)
    }

    @Test fun offsets_still_point_into_the_chapter() {
        val text = "Narration. \"A line,\" said Ada. More narration, and then \"another,\" said Bram."
        val spans = SpeechCast.attribute(text)
        val pieces = ReaderLogic.ttsPieces(text, spans)
        assertTrue(pieces.size >= 3)
        for (p in pieces) {
            val at = text.substring(p.start)
            // The piece is the text from its offset, up to the whitespace the chunking normalises.
            assertTrue("offset ${p.start} does not land on \"${p.text.take(12)}\"", at.trimStart().startsWith(p.text.take(12).trimStart()))
            assertEquals(SpeechCast.speakerAt(spans, p.start), p.speaker)
        }
    }

    @Test fun without_spans_nothing_changes() {
        val text = "One sentence. Two sentences. Three."
        val pieces = ReaderLogic.ttsPieces(text)
        assertEquals(1, pieces.size)
        assertEquals("", pieces[0].speaker)
        assertEquals(0, pieces[0].start)
    }

    @Test fun a_chapter_of_narration_is_never_split_for_no_reason() {
        val text = "The train was late. " + "It stayed late for an hour. ".repeat(20)
        val pieces = ReaderLogic.ttsPieces(text, SpeechCast.attribute(text))
        assertEquals(1, pieces.size)
    }
}
