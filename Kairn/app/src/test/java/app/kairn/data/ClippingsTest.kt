package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClippingsTest {

    private val file = "\uFEFF" + listOf(
        "The Left Hand of Darkness (Le Guin, Ursula K.)",
        "- Your Highlight on page 42 | Location 631-633 | Added on Monday, 12 March 2018 21:04:11",
        "",
        "It is good to have an end to journey toward; but it is the journey that matters, in the end.",
        "==========",
        "The Left Hand of Darkness (Le Guin, Ursula K.)",
        "- Your Note on page 44 | Location 700 | Added on Monday, 12 March 2018 21:09:02",
        "",
        "Genly is the alien here, not Estraven.",
        "==========",
        "Piranesi (Clarke, Susanna)",
        "- Your Bookmark on page 9 | Location 120 | Added on Tuesday, 13 March 2018 08:00:00",
        "",
        "==========",
        "A Title With (Brackets) In It (Some, Author)",
        "- Your Highlight on page 1 | Location 1-2 | Added on Tuesday, 13 March 2018 08:05:00",
        "",
        "The Beauty of the House is immeasurable; its Kindness infinite.",
        "==========",
    ).joinToString("\r\n")

    @Test
    fun `highlights and notes are parsed, bookmarks are dropped`() {
        val c = Clippings.parse(file)
        assertEquals(3, c.size)
        assertEquals("The Left Hand of Darkness", c[0].title)
        assertEquals("Le Guin, Ursula K.", c[0].author)
        assertEquals("highlight", c[0].kind)
        assertTrue(c[0].quote.startsWith("It is good to have an end"))
        assertEquals("note", c[1].kind)
        assertEquals("Genly is the alien here, not Estraven.", c[1].note)
        assertEquals("", c[1].quote)
    }

    @Test
    fun `a title that carries brackets keeps its own words`() {
        val c = Clippings.parse(file).last()
        assertEquals("A Title With (Brackets) In It", c.title)
        assertEquals("Some, Author", c.author)
    }

    @Test
    fun `a repeated book title inside the body is not part of the quote`() {
        val blocks = listOf(
            "Dune (Herbert, Frank)",
            "- Your Highlight on page 3 | Location 40-42 | Added on Monday, 1 January 2024 10:00:00",
            "",
            "Dune (Herbert, Frank)",
            "A beginning is the time for taking the most delicate care.",
            "==========",
        ).joinToString("\n")
        val c = Clippings.parse(blocks).single()
        assertFalse(c.quote.contains("Herbert"))
        assertTrue(c.quote.startsWith("A beginning is the time"))
    }

    @Test
    fun `garbage produces nothing instead of a crash`() {
        assertTrue(Clippings.parse("").isEmpty())
        assertTrue(Clippings.parse("just some text\nwith no blocks").isEmpty())
        assertTrue(Clippings.parse("==========\n==========").isEmpty())
    }

    @Test
    fun `matching ignores punctuation, case and a missing author`() {
        val book = Book("id", "The Left Hand of Darkness", "Ursula K. Le Guin", "uri")
        assertTrue(Clippings.matches(Clipping("The Left Hand of Darkness", "Le Guin, Ursula K.", "q", "", "highlight"), book))
        assertTrue(Clippings.matches(Clipping("left hand of darkness", "", "q", "", "highlight"), book))
        assertFalse(Clippings.matches(Clipping("Piranesi", "Clarke, Susanna", "q", "", "highlight"), book))
    }

    @Test
    fun `the queries go from long to short and never below four characters`() {
        val q = Clippings.queries(Clipping("t", "a", "This is a highlight long enough to be found in the text of the book itself.", "", "highlight"))
        assertEquals(3, q.size)
        assertTrue(q[0].length >= q[1].length)
        assertTrue(q.last().length >= 4)
        assertTrue(Clippings.queries(Clipping("t", "a", "abc", "", "highlight")).isEmpty())
    }
}
