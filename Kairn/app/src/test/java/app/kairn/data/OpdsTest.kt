package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPDS is Atom as Calibre actually serves it: relative hrefs, a navigation entry per folder, one
 * `acquire`d link per book. These tests are built from the responses a Calibre content server gives, plus
 * the two things a hostile catalog would try.
 */
class OpdsTest {

    private val base = "http://192.168.1.5:8080/opds"

    private val calibre = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:opds="http://opds-spec.org/2010/catalog"
              xmlns:dc="http://purl.org/dc/terms/">
          <id>urn:calibre:library</id>
          <title>Calibre Library</title>
          <updated>2024-01-02T10:00:00+00:00</updated>
          <entry>
            <title>Discworld</title>
            <id>urn:calibre:nav:1</id>
            <updated>2024-01-02T10:00:00+00:00</updated>
            <content type="text">6 books</content>
            <link href="/opds/navcatalog/1/books" rel="subsection"
                  type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
          </entry>
          <entry>
            <title>Guards! Guards!</title>
            <id>urn:calibre:book:12</id>
            <author><name>Terry Pratchett</name></author>
            <dc:language>en</dc:language>
            <updated>2023-11-04T09:30:00+00:00</updated>
            <content type="text">&lt;p&gt;A city in crisis.&lt;/p&gt;</content>
            <link href="/get/epub/12" rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>
            <link href="/get/jpg/12" rel="http://opds-spec.org/image" type="image/jpeg"/>
            <link href="/get/jpg/12?size=1" rel="http://opds-spec.org/image/thumbnail" type="image/jpeg"/>
          </entry>
          <link rel="next" href="/opds/books?page=2" type="application/atom+xml"/>
        </feed>
    """.trimIndent()

    @Test fun a_calibre_page_becomes_folders_and_books() {
        val feed = Opds.parse(calibre, base)!!
        assertEquals("Calibre Library", feed.title)
        assertEquals(2, feed.entries.size)

        val folder = feed.entries[0]
        assertTrue("a navigation entry must be one", folder.navigation)
        assertEquals("http://192.168.1.5:8080/opds/navcatalog/1/books", folder.sub)

        val book = feed.entries[1]
        assertTrue(!book.navigation)
        assertEquals("Guards! Guards!", book.title)
        assertEquals("Terry Pratchett", book.author)
        assertEquals("http://192.168.1.5:8080/get/epub/12", book.epub)
        // The thumbnail is preferred: it is the one a row can afford.
        assertEquals("http://192.168.1.5:8080/get/jpg/12?size=1", book.cover)
        assertEquals("A city in crisis.", book.summary)
        assertTrue(book.updated > 0)
    }

    @Test fun the_next_page_is_resolved_against_the_page_it_came_from() {
        val feed = Opds.parse(calibre, base)!!
        assertEquals("http://192.168.1.5:8080/opds/books?page=2", feed.next)
    }

    @Test fun an_absolute_href_is_left_alone() {
        val xml = calibre.replace("/get/epub/12", "https://books.example.org/get/12.epub")
        assertEquals("https://books.example.org/get/12.epub", Opds.parse(xml, base)!!.entries[1].epub)
    }

    @Test fun a_page_that_is_not_a_feed_is_not_a_catalog() {
        assertNull(Opds.parse("<html><body>hello</body></html>", base))
        assertNull(Opds.parse("not xml", base))
        assertNull(Opds.parse("", base))
    }

    @Test fun a_feed_with_no_books_is_still_a_feed() {
        val empty = """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Empty shelf</title></feed>
        """.trimIndent()
        val feed = Opds.parse(empty, base)!!
        assertTrue(feed.entries.isEmpty())
        assertNull(feed.next)
    }

    @Test fun a_doctype_with_an_external_entity_does_not_read_the_file_system() {
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE feed [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>&xxe;</title>
              <entry><title>Book</title></entry>
            </feed>
        """.trimIndent()
        val feed = Opds.parse(hostile, base)
        // Refused entirely, or read with the entity empty: what must never happen is /etc/passwd in a title.
        if (feed != null) {
            assertTrue(feed.entries.none { it.title.contains("root:") })
            assertTrue(feed.title?.contains("root:") != true)
        }
    }

    @Test fun an_href_that_is_not_http_is_never_followed() {
        assertNull(Opds.resolve(base, "javascript:alert(1)"))
        assertNull(Opds.resolve(base, "file:///etc/passwd"))
        assertNull(Opds.resolve(base, ""))
        assertEquals("http://192.168.1.5:8080/x", Opds.resolve(base, "/x"))
    }

    @Test fun a_summary_cannot_take_over_a_row() {
        val xml = calibre.replace("A city in crisis.", "word ".repeat(500))
        val summary = Opds.parse(xml, base)!!.entries[1].summary!!
        assertTrue(summary.length <= 400)
    }
}
