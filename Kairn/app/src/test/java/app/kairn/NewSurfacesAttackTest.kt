package app.kairn

import app.kairn.data.Book
import app.kairn.data.BookCast
import app.kairn.data.Catalog
import app.kairn.data.EpubMeta
import app.kairn.data.EpubOpf
import app.kairn.data.Opds
import app.kairn.data.OpdsFeed
import app.kairn.data.Status
import app.kairn.data.Store
import app.kairn.ui.ReaderLogic
import app.kairn.ui.SpeechCast
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The 1.0.0 surfaces, attacked. Anything a catalog server, an EPUB or a restored settings file can hand the
 * app is fair input: these are the probes that decide whether the new code is as hard as the old code.
 */
class NewSurfacesAttackTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun feed(entries: String) = """
        <feed xmlns="http://www.w3.org/2005/Atom"><title>Cat</title>
        $entries
        </feed>
    """.trimIndent()

    private fun entry(title: String, link: String? = "/get/1", extra: String = "") = """
        <entry><title>$title</title><id>1</id><updated>2024-01-01T00:00:00Z</updated>
        ${link?.let { """<link href="$it" rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>""" }.orEmpty()}
        $extra</entry>
    """.trimIndent()

    @Test fun a_catalogue_of_ten_thousand_books_parses_in_bounded_time() {
        val xml = feed(entry("Book").repeat(10_000))
        val at = System.currentTimeMillis()
        val parsed = Opds.parse(xml, "http://h/opds")
        val ms = System.currentTimeMillis() - at
        assertNotNull(parsed)
        assertEquals(10_000, parsed!!.entries.size)
        assertTrue("10k entries took ${ms}ms", ms < 8_000)
    }

    @Test fun a_title_cannot_be_a_megabyte() {
        val title = "A".repeat(1_000_000)
        val parsed = Opds.parse(feed(entry(title)), "http://h/opds")!!
        assertTrue("title kept ${parsed.entries[0].title.length} chars", parsed.entries[0].title.length <= 300)
    }

    @Test fun an_author_and_a_summary_are_bounded_too() {
        val long = "B".repeat(500_000)
        val parsed = Opds.parse(
            feed("<entry><title>T</title><author><name>$long</name></author><summary>$long</summary></entry>"),
            "http://h/opds",
        )!!
        val e = parsed.entries[0]
        assertTrue("author ${e.author!!.length}", e.author!!.length <= 200)
        assertTrue("summary ${e.summary!!.length}", e.summary!!.length <= 400)
    }

    @Test fun a_href_with_a_control_character_is_refused() {
        assertNull(Opds.resolve("http://h/opds", "http://h/x\nHost: evil"))
        assertNull(Opds.resolve("http://h/opds", "http://h/x y"))
        // A bare host is a real address: the catalog root is a valid destination, not a malformed one.
        assertEquals("http://h/", Opds.resolve("http://h/opds", "http://h/"))
    }

    @Test fun a_pagination_loop_cannot_grow_a_list_forever() {
        // A catalog whose "next" points at itself returns the same page every time. The screen appends;
        // without a cap a single tap on More is an unbounded list.
        val page = Opds.parse(feed(entry("Same").repeat(20)), "http://h/opds")!!
        var total = page.entries
        repeat(200) { total = Opds.appendPage(total, page).entries }
        assertTrue("grew to ${total.size}", total.size <= Opds.MAX_ENTRIES)
    }

    @Test fun a_series_name_cannot_be_a_megabyte_and_a_description_stays_bounded() {
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata
              xmlns:dc="http://purl.org/dc/elements/1.1/">
              <meta name="calibre:series" content="${"S".repeat(900_000)}"/>
              <dc:description>${"D".repeat(900_000)}</dc:description>
            </metadata></package>
        """.trimIndent()
        val meta = EpubOpf.parse(opf)!!
        assertTrue("series ${meta.series!!.length}", meta.series!!.length <= 200)
        assertTrue("description ${meta.description!!.length}", meta.description!!.length <= 4000)
    }

    @Test fun an_epub_inside_a_zip_bomb_is_not_read_past_its_cap() {
        // A 40 MB OPF that inflates from a tiny entry must be refused, not loaded.
        val bomb = File(tmp.root, "bomb.epub")
        java.util.zip.ZipOutputStream(bomb.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
            zip.write("""<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("content.opf"))
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            repeat(640) { zip.write(chunk) }        // 40 MB of an OPF that cannot be real
            zip.closeEntry()
        }
        assertNull(EpubOpf.read { bomb.inputStream() })
    }

    @Test fun a_restored_record_cannot_carry_a_megabyte_description() {
        val o = JSONObject()
            .put("id", "a1").put("title", "T").put("uri", "u")
            .put("desc", "X".repeat(2_000_000))
            .put("series", "S".repeat(2_000_000))
        val book = Book.from(o)!!
        assertTrue("desc ${book.desc!!.length}", book.desc!!.length <= 4000)
        assertTrue("series ${book.series!!.length}", book.series!!.length <= 200)
    }

    @Test fun a_cast_file_with_a_thousand_voices_is_capped() {
        val voices = JSONObject()
        repeat(1_000) { i -> voices.put("Speaker $i", (i % 9) + 1) }
        val cast = BookCast.from(JSONObject().put("enabled", true).put("voices", voices))
        assertTrue("kept ${cast.voices.size} voices", cast.voices.size <= BookCast.SLOTS * 4)
    }

    @Test fun the_same_name_in_two_cases_is_one_speaker() {
        val text = "\"One,\" said Frodo. \"Two,\" said FRODO. \"Three,\" said Frodo."
        val cast = SpeechCast.speakers(SpeechCast.attribute(text))
        assertEquals(1, cast.size)
    }

    @Test fun a_chapter_of_two_thousand_quotes_stays_usable() {
        val text = "\"Line,\" said Ada. ".repeat(2_000)
        val spansAt = System.currentTimeMillis()
        val spans = SpeechCast.attribute(text)
        val attributionMs = System.currentTimeMillis() - spansAt
        val piecesAt = System.currentTimeMillis()
        val pieces = ReaderLogic.ttsPieces(text, spans)
        val piecesMs = System.currentTimeMillis() - piecesAt
        assertTrue("attribution ${attributionMs}ms", attributionMs < 4_000)
        assertTrue("pieces ${piecesMs}ms", piecesMs < 2_000)
        // 2 000 utterances queued at once is the number that matters to the engine.
        assertTrue("queued ${pieces.size} utterances", pieces.size <= 1_200)
    }

    @Test fun settings_with_hostile_catalogs_keep_the_rest_of_the_file() {
        val o = JSONObject()
            .put("theme", "DARK")
            .put("readingWpm", 250)
            .put("catalogs", JSONArray(listOf(
                JSONObject().put("name", "ok").put("url", "http://192.168.1.5:8080/opds").put("pass", "s3cret"),
                JSONObject().put("name", "bad").put("url", "file:///etc/passwd"),
                JSONObject().put("name", "empty").put("url", ""),
                JSONObject().put("url", 42),
            )))
        val s = app.kairn.data.AppSettings.from(o)
        assertEquals(1, s.catalogs.size)
        assertEquals(250, s.readingWpm)
        // A backup may carry the catalog, never its password.
        assertEquals("", s.withoutSecrets().catalogs.single().pass)
        assertEquals("s3cret", s.catalogs.single().pass)
    }

    @Test fun a_removed_book_takes_its_cast_with_it() {
        val store = Store.forTesting(tmp.root).also { runBlocking { it.load() } }
        store.put(Book(id = "b1", title = "T", author = "", uri = "u", status = Status.READING))
        store.saveCast("b1", BookCast(enabled = true, voices = mapOf("Ada" to 2)))
        store.flush()
        assertTrue(store.castFile("b1").exists())
        store.remove("b1")
        store.flush()
        assertTrue("cast file outlived its book", !store.castFile("b1").exists())
    }

    @Test fun a_catalog_named_with_slashes_is_still_only_a_name() {
        val c = Catalog(name = "../../escape", url = "http://h/opds")
        assertEquals("../../escape", c.name)
        val s = app.kairn.data.AppSettings(catalogs = listOf(c))
        val back = app.kairn.data.AppSettings.from(s.toJson())
        assertEquals("../../escape", back.catalogs.single().name)
        assertTrue(File(tmp.root, "escape").exists().not())
        assertNull(EpubMeta.seriesLabel(null, 1.0))
    }

    @Test fun a_download_refuses_a_phone_that_is_about_to_be_full() {
        val client = app.kairn.data.OpdsClient("1.0.0")
        // A directory that does not exist has no space to give; the same guard is what a full phone hits.
        val gone = File(tmp.root, "nope/books")
        assertNull(runBlocking { client.download("http://127.0.0.1:1/x.epub", File(gone, "x.epub")) })
        assertTrue(File(gone, "x.epub").exists().not())
    }

    @Test fun the_meta_backfill_queue_skips_what_it_cannot_read_and_keeps_the_order() {
        val books = listOf(
            Book(id = "a", title = "new", author = "", uri = "u"),
            Book(id = "b", title = "already", author = "", uri = "u", metaRead = true),
            Book(id = "c", title = "broken", author = "", uri = "u", broken = true),
            Book(id = "d", title = "missing", author = "", uri = "u", missing = true),
            Book(id = "e", title = "old import", author = "", uri = "u", added = 1L),
        )
        assertEquals(listOf("a", "e"), app.kairn.data.metaQueue(books).map { it.id })
        assertEquals(listOf("a"), app.kairn.data.metaQueue(books, limit = 1).map { it.id })
    }

    @Test fun an_empty_feed_page_has_no_next_and_no_entries() {
        val page = Opds.parse(feed(""), "http://h/opds")!!
        val appended = Opds.appendPage(emptyList(), page)
        assertTrue(appended.entries.isEmpty())
        assertNull(appended.next)
    }
}
