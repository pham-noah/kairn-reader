package app.kairn

import app.kairn.data.Book
import app.kairn.data.ReaderPrefs
import app.kairn.data.Store
import app.kairn.data.Update
import app.kairn.data.mergeBook
import app.kairn.ui.ReaderLogic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Second adversarial pass: identity, recursion, locale, and the update path. */
class SecondBreakPassTest {

    // ---------- book ids are file names ----------

    @Test fun a_crafted_record_id_cannot_write_outside_the_data_folder() {
        val base = Files.createTempDirectory("kairn-id").toFile()
        val store = Store.forTesting(base)
        // A restored record is read with optString("id") and nothing checks what it holds.
        val evil = Book.from(JSONObject().put("id", "../../escaped").put("uri", "file:///a.epub").put("title", "T"))
        assertNull("a record with a path for an id is not a record", evil)
        // And even one built by hand is refused at the write.
        store.put(Book(id = "../../escaped", title = "T", author = "", uri = "file:///a.epub"))
        store.flush()
        assertTrue("a record id wrote outside data/: ${File(base, "escaped.json").exists()}",
            !File(base, "escaped.json").exists())
    }

    @Test fun removing_a_crafted_id_cannot_delete_outside_the_data_folder() {
        val base = Files.createTempDirectory("kairn-id2").toFile()
        val victim = File(base, "victim.json").apply { writeText("keep me") }
        val store = Store.forTesting(base)
        store.put(Book(id = "../../victim", title = "T", author = "", uri = "file:///a.epub"))
        store.flush()
        store.remove("../../victim")
        store.flush()
        assertTrue("a crafted id deleted a file outside data/", victim.exists())
    }

    // ---------- ids must not depend on the phone's language ----------

    @Test fun a_hex_book_id_is_ascii_in_every_locale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "hi-IN", "fa-IR", "th-TH-u-nu-thai")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val id = "%02x".format(0xab)
                assertTrue("hex id under $tag was [$id]", id == "ab")
                val big = ByteArray(8) { 0xAB.toByte() }.joinToString("") { "%02x".format(it) }
                assertTrue("id under $tag was [$big]", big.all { it in "0123456789abcdef" } && big.length == 16)
            }
        } finally {
            Locale.setDefault(saved)
        }
    }

    // ---------- table of contents recursion ----------

    @Test fun a_deeply_nested_toc_does_not_blow_the_stack() {
        fun chain(depth: Int): ReaderLogic.TocNode {
            var node = ReaderLogic.TocNode("ch0.xhtml", "Chapter 0")
            repeat(depth) { i -> node = ReaderLogic.TocNode("ch${i + 1}.xhtml", "Chapter ${i + 1}", listOf(node)) }
            return node
        }
        val map = ReaderLogic.chapterTitles(listOf(chain(20_000)), emptyList())
        assertTrue("a bounded walk still names the top of the list", map.isNotEmpty())
        assertTrue("the walk must stop, not recurse forever: ${map.size}", map.size <= 65)
    }

    // ---------- the update check ----------

    @Test fun a_hostile_version_tag_cannot_crash_the_update_check() {
        // A "latest release" tag is remote text; it must read as "not newer", not throw.
        assertTrue(!Update.isNewer("9.9.99999999999999999", "0.9.5"))
        assertTrue(!Update.isNewer("", "0.9.5"))
        assertTrue(!Update.isNewer("9.9.99999999999999999", "9.9.99999999999999999"))
    }

    @Test fun version_comparison_is_numeric_not_lexical() {
        assertTrue(Update.isNewer("0.10.0", "0.9.5"))
        assertTrue(Update.isNewer("v0.9.6", "0.9.5"))
        assertTrue(Update.isNewer("release-1.0.0", "0.9.9"))
        assertTrue(!Update.isNewer("0.9.5", "0.9.5"))
        assertTrue(!Update.isNewer("0.9.4", "0.9.5"))
        assertTrue(!Update.isNewer("nightly", "0.9.5"))
        assertEquals(false, Update.isNewer("0.9.5.1", "0.9.5"))
    }

    // ---------- merging records ----------

    @Test fun merging_two_unreadable_records_keeps_ours_and_does_not_throw() {
        val dir = Files.createTempDirectory("kairn-mb").toFile()
        val target = File(dir, "b.json").apply { writeText("{ broken") }
        mergeBook(target, "{ also broken".toByteArray(), replace = false)
        assertEquals("{ broken", target.readText())
        mergeBook(target, "{\"id\":\"x\",\"uri\":\"file:///x.epub\"}".toByteArray(), replace = false)
        assertTrue(target.readText().contains("file:///x.epub"))
    }

    @Test fun a_merge_never_drops_a_mark_from_either_side() {
        val dir = Files.createTempDirectory("kairn-mb2").toFile()
        val target = File(dir, "b.json")
        fun rec(id: String, markId: String, note: String, lastRead: Long) = Book(
            id = "b", title = "B", author = "", uri = "file:///b.epub", lastRead = lastRead,
            marks = listOf(app.kairn.data.Mark(markId, "note", 0, "q", note, "{\"href\":\"c.xhtml\"}", 1)),
        ).toJson().toString()
        target.writeText(rec("b", "m1", "mine", 1000))
        mergeBook(target, rec("b", "m2", "theirs", 2000).toByteArray(), replace = false)
        val merged = Book.from(JSONObject(target.readText()))!!
        assertEquals(setOf("m1", "m2"), merged.marks.map { it.id }.toSet())
        assertEquals(2000L, merged.lastRead)
    }

    // ---------- prefs that came off a wire ----------

    @Test fun absurd_reader_prefs_do_not_reach_the_renderer_unclamped() {
        val p = ReaderPrefs.from(JSONObject("{\"fontScale\":1e9,\"lineHeight\":-5,\"margins\":1e308,\"scrollSpeed\":-1}"))
        assertTrue("fontScale ${p.fontScale}", p.fontScale in 0.5..4.0)
        assertTrue("lineHeight ${p.lineHeight}", p.lineHeight in 0.8..3.0)
        assertTrue("margins ${p.margins}", p.margins in 0.0..3.0)
        assertTrue("scrollSpeed ${p.scrollSpeed}", p.scrollSpeed >= 1f)
    }

    // ---------- store under a file that is not a file ----------

    @Test fun a_target_that_is_a_directory_does_not_lose_the_previous_record() {
        val dir = Files.createTempDirectory("kairn-dir").toFile()
        val target = File(dir, "b.json")
        target.writeText("{\"id\":\"b\"}")
        val blocker = File(dir, "b.json.tmp")
        blocker.mkdirs()                                   // a directory where the temp file goes
        Store.writeAtomic(target, "{}")                    // must fail quietly, not truncate the record
        assertEquals("{\"id\":\"b\"}", target.readText())
        assertNull(null)
    }
}
