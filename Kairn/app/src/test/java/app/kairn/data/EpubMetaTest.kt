package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OPF is the only place a Kairn library learns a book belongs to a series, and the only place a detail
 * sheet gets a description. These tests feed it the three files that actually turn up: a Calibre export, an
 * EPUB 3 with a proper collection, and a hostile one.
 */
class EpubMetaTest {

    private fun opf(body: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
            $body
          </metadata>
          <manifest/>
        </package>
    """.trimIndent()

    @Test fun `calibre's private series meta is read, with its index`() {
        val meta = EpubOpf.parse(opf("""
            <dc:title>Guards! Guards!</dc:title>
            <meta name="calibre:series" content="Discworld"/>
            <meta name="calibre:series_index" content="8"/>
            <dc:publisher>Gollancz</dc:publisher>
            <dc:date>1989-11-11</dc:date>
            <dc:language>en</dc:language>
        """))!!
        assertEquals("Discworld", meta.series)
        assertEquals(8.0, meta.seriesIndex!!, 0.001)
        assertEquals("Gollancz", meta.publisher)
        assertEquals("1989-11-11", meta.published)
        assertEquals("en", meta.language)
    }

    @Test fun `an EPUB 3 collection counts as a series only when it says so`() {
        val series = EpubOpf.parse(opf("""
            <meta property="belongs-to-collection" id="c1">The Broken Earth</meta>
            <meta refines="#c1" property="collection-type">series</meta>
            <meta refines="#c1" property="group-position">2</meta>
        """))!!
        assertEquals("The Broken Earth", series.series)
        assertEquals(2.0, series.seriesIndex!!, 0.001)

        // A reading-order collection ("set", an imprint, a book club) is not a series: sorting by it would
        // scatter a library into invented shelves.
        val notSeries = EpubOpf.parse(opf("""
            <meta property="belongs-to-collection" id="c2">Penguin Classics</meta>
            <meta refines="#c2" property="collection-type">set</meta>
        """))!!
        assertNull(notSeries.series)
    }

    @Test fun `a description loses its markup and keeps its words`() {
        val meta = EpubOpf.parse(opf("""
            <dc:description>&lt;p&gt;A &lt;b&gt;city&lt;/b&gt; in crisis.&lt;/p&gt;&lt;br/&gt;
            &lt;p&gt;The &amp;amp; sign survives.&lt;/p&gt;</dc:description>
        """))!!
        val d = meta.description!!
        assertTrue(d, d.contains("A city in crisis."))
        assertTrue(d, d.contains("& sign"))
        assertTrue(d, !d.contains("<p>"))
        assertTrue(d, !d.contains("&amp;"))
    }

    @Test fun `a description cannot run away with the record`() {
        val long = "<dc:description>" + "word ".repeat(4000) + "</dc:description>"
        val meta = EpubOpf.parse(opf(long))!!
        assertTrue(meta.description!!.length <= 4000)
    }

    @Test fun `a doctype that names an external entity resolves to nothing and does not throw`() {
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE package [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <package xmlns="http://www.idpf.org/2007/opf"><metadata
              xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:description>&xxe;</dc:description>
              <meta name="calibre:series" content="Still Fine"/>
            </metadata></package>
        """.trimIndent()
        // Either the parse is refused (null) or it is read with the entity empty. What must never happen is
        // the file being resolved and its contents landing in a record.
        val meta = EpubOpf.parse(hostile)
        if (meta != null) {
            assertTrue("entity text must not be resolved: ${meta.description}", meta.description?.contains("root:") != true)
        }
    }

    @Test fun `garbage is not a book and not a crash`() {
        assertNull(EpubOpf.parse(""))
        assertNull(EpubOpf.parse("not xml at all"))
        assertNull(EpubOpf.parse("<html><body>hello</body></html>"))
    }

    @Test fun `the series label says the number only when there is one to say`() {
        assertEquals("Discworld 8", EpubMeta.seriesLabel("Discworld", 8.0))
        assertEquals("Discworld 1", EpubMeta.seriesLabel("Discworld", 1.0))
        // Half a book into a series of novellas: keep the fraction rather than rounding it away.
        assertEquals("Wayward 2.5", EpubMeta.seriesLabel("Wayward", 2.5))
        assertEquals("Discworld", EpubMeta.seriesLabel("Discworld", null))
        assertEquals("Discworld", EpubMeta.seriesLabel("Discworld", 0.0))
        assertNull(EpubMeta.seriesLabel(null, 3.0))
        assertNull(EpubMeta.seriesLabel("   ", 3.0))
        assertEquals("Discworld 8", EpubMeta.seriesLabel("  Discworld  ", 8.0))
    }

    @Test fun `an opf with none of these fields is empty, not an error`() {
        val meta = EpubOpf.parse(opf("<dc:title>Only a title</dc:title>"))!!
        assertTrue(meta.isEmpty)
    }
}
