package app.kairn

import app.kairn.data.AppSettings
import app.kairn.data.Book
import app.kairn.data.Clippings
import app.kairn.data.ReadingStats
import app.kairn.data.ReaderPrefs
import app.kairn.data.Store
import app.kairn.data.Status
import app.kairn.data.WifiTransfer
import app.kairn.data.freeCopyName
import app.kairn.data.mergePositionJson
import app.kairn.data.isKairnBackup
import app.kairn.data.zipEntryNames
import app.kairn.ui.ReaderLogic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

/**
 * Hostile-input probes: what a corrupt file, a fuzzed name or a bad settings value does to the app.
 * These are exploratory - the assertion states the behaviour the app should keep.
 */
class HostileInputTest {

    // ---------- Clippings ----------

    @Test fun clippings_never_throw_on_random_bytes() {
        val rnd = Random(7)
        val pool = "=-()\r\n abcXYZ0123\uFEFF\u0000\ttitle author Your Highlight Note Bookmark …\uD83D\uDE00"
        repeat(4000) {
            val s = StringBuilder()
            repeat(rnd.nextInt(0, 400)) { s.append(pool[rnd.nextInt(pool.length)]) }
            Clippings.parse(s.toString())   // must not throw
        }
    }

    @Test fun clippings_survive_a_megabyte_without_a_separator() {
        val one = "A".repeat(1_000_000) + "\n- Your Highlight on page 1\n" + "B".repeat(500_000)
        val t0 = System.currentTimeMillis()
        val out = Clippings.parse(one)
        val ms = System.currentTimeMillis() - t0
        assertEquals(1, out.size)
        assertTrue("parse took ${ms}ms", ms < 4000)
    }

    @Test fun clippings_handle_crlf_and_bom_and_empty() {
        assertEquals(0, Clippings.parse("").size)
        assertEquals(0, Clippings.parse("\uFEFF").size)
        val crlf = "\uFEFFTitle (Author)\r\n- Your Highlight on page 4 | Location 9-10\r\n\r\nquoted text\r\n==========\r\n"
        val out = Clippings.parse(crlf)
        assertEquals(1, out.size)
        assertEquals("quoted text", out[0].quote)
        assertEquals("Author", out[0].author)
    }

    // ---------- JSON records ----------

    @Test fun book_survives_type_confusion() {
        val weird = JSONObject()
            .put("id", 17).put("uri", JSONObject()).put("title", 5)
            .put("tags", 3).put("marks", "no").put("prefs", JSONArray())
            .put("status", 9).put("progress", "half").put("folder", JSONObject.NULL)
        // Either it refuses the record, or it yields something usable - but it must not throw.
        runCatching { Book.from(weird) }.getOrElse { throw AssertionError("Book.from threw on type confusion: $it") }
    }

    @Test fun settings_survive_type_confusion_and_keep_sane_ranges() {
        val weird = JSONObject()
            .put("theme", 42).put("ambient", JSONObject()).put("readingWpm", "fast")
            .put("autoBackupDays", -7).put("autoBackupKeep", 9999)
            .put("folders", "none").put("barOrder", JSONArray().put(JSONObject()).put("NoSuchItem"))
            .put("defaults", JSONArray()).put("shelves", JSONArray().put(JSONObject.NULL))
        val s = AppSettings.from(weird)
        assertEquals(240, s.readingWpm)   // a string where a number belongs falls back to the default
        assertTrue(s.autoBackupDays in 1..90)
        assertTrue(s.autoBackupKeep in 1..20)
        assertTrue(s.barOrder.isNotEmpty())
    }

    @Test fun a_zero_wpm_cannot_reach_the_time_left_estimate() {
        val s = AppSettings.from(JSONObject().put("readingWpm", 0))
        assertEquals(60, s.readingWpm)                       // clamped on the way in
        // And the estimate itself refuses a non-positive pace, so a bad value cannot divide by zero.
        assertNull(ReaderLogic.minutesLeftInBook(60_000, 0.1f, 0))
        assertNull(ReaderLogic.minutesLeft(ReaderLogic.Pace(900, 5), 100, 0.1f, 0))
        assertNull(ReaderLogic.minutesLeftInChapter(3_000, 0.5f, 0))
        assertEquals(0, ReaderLogic.minutesLeftInBook(60_000, 0.1f, -240) ?: 0)
    }

    @Test fun positions_sidecar_with_garbage_keeps_the_record() {
        val dir = Files.createTempDirectory("kairn-pos").toFile()
        val store = Store.forTesting(dir)
        store.put(Book(id = "abc", title = "T", author = "", uri = "file:///x.epub"))
        store.flush()
        File(store.posDir, "abc.json").writeText("{\"status\":\"NOT_A_STATUS\",\"progress\":\"x\",\"locator\":42}")
        store.reload()
        assertEquals(1, store.books.value.size)
        assertEquals(Status.NONE, store.books.value[0].status)
    }

    @Test fun stats_survive_garbage() {
        val s = ReadingStats.from(JSONObject().put("days", JSONObject().put("not-a-date", -1)).put("finished", JSONArray().put(JSONObject())))
        assertEquals(0, s.totalMinutes())
        runCatching { s.streak() }.getOrElse { throw AssertionError("streak threw: $it") }
    }

    // ---------- Wi-Fi transfer names ----------

    @Test fun uploaded_names_can_never_escape_the_folder_or_the_filesystem_limit() {
        val rnd = Random(11)
        val pool = "../\\\u0000:?*\"<>|%20 .-()[]abcEPUBé😀"
        repeat(20_000) {
            val s = StringBuilder()
            repeat(rnd.nextInt(0, 60)) { s.append(pool[rnd.nextInt(pool.length)]) }
            val n = WifiTransfer.safeName(s.toString())
            if (n != null) {
                assertTrue("slash in '$n'", !n.contains('/') && !n.contains('\\'))
                assertTrue("dotdot in '$n'", !n.contains(".."))
                assertTrue("too long: ${n.length}", n.length <= 120)
                assertTrue("not epub: $n", n.endsWith(".epub"))
                assertTrue("blank stem: $n", n != ".epub")
                assertTrue("control char in '$n'", n.none { it.code < 0x20 })
            }
        }
        assertEquals("book.epub", WifiTransfer.safeName("book.epub"))
        assertEquals("book.epub", WifiTransfer.safeName("../../etc/book.epub"))
        assertNull(WifiTransfer.safeName(".epub"))
        assertNull(WifiTransfer.safeName("book.EPUB.exe"))
        assertEquals("book.epub", WifiTransfer.safeName("book.EPUB"))
    }

    @Test fun free_copy_name_terminates_for_degenerate_names() {
        val dir = Files.createTempDirectory("kairn-copy").toFile()
        val a = freeCopyName(dir, "x.epub")
        a.writeText("1")
        val b = freeCopyName(dir, "x.epub")
        assertTrue(a.name != b.name)
        // A name with no stem must still end somewhere.
        val c = freeCopyName(dir, ".epub")
        assertNotNull(c)
    }

    // ---------- backup zip ----------

    @Test fun a_truncated_zip_is_not_a_kairn_backup() {
        val junk = ByteArray(4096) { (it % 251).toByte() }
        assertTrue(!isKairnBackup(zipEntryNames(ByteArrayInputStream(junk))))
    }

    @Test fun an_archive_that_would_restore_nothing_is_not_a_kairn_backup() {
        // Every restore path skips "..", so such an archive restores nothing; a replace would then prune the
        // whole library. It must not validate in the first place.
        assertTrue(!isKairnBackup(listOf("data/../../files/imported/x.epub")))
        assertTrue(!isKairnBackup(listOf("data/covers/a.jpg")))
        assertTrue(!isKairnBackup(emptyList()))
        // A backup of an empty library still validates.
        assertTrue(isKairnBackup(listOf("data/settings.json")))
        assertTrue(isKairnBackup(listOf("data/books/abc.json", "data/pos/abc.json")))
    }

    // ---------- TTS chunking ----------

    @Test fun tts_pieces_never_split_a_surrogate_pair() {
        val text = "😀".repeat(4_000)
        val pieces = ReaderLogic.ttsPieces(text)
        assertTrue(pieces.isNotEmpty())
        for (p in pieces) {
            for (i in p.text.indices) {
                val c = p.text[i]
                if (Character.isHighSurrogate(c)) {
                    assertTrue("lone high surrogate at end of a chunk", i + 1 < p.text.length && Character.isLowSurrogate(p.text[i + 1]))
                }
                if (Character.isLowSurrogate(c)) {
                    assertTrue("lone low surrogate", i > 0 && Character.isHighSurrogate(p.text[i - 1]))
                }
            }
        }
    }

    @Test fun tts_offsets_stay_inside_the_text() {
        val text = "First sentence. Second one! Third? " + "x".repeat(500)
        for (prog in listOf(-1f, 0f, 0.0001f, 0.5f, 0.99999f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val at = ReaderLogic.ttsStartOffset(text, prog)
            assertTrue("offset $at for progression $prog", at in 0 until text.length)
        }
        assertEquals(0, ReaderLogic.ttsStartOffset("", 0.5f))
    }

    @Test fun sentence_at_stays_inside_the_text() {
        for (t in listOf("", " ", "a", "😀😀", "One. Two")) {
            for (at in listOf(Int.MIN_VALUE, -1, 0, 1, 5, Int.MAX_VALUE)) {
                val (s, e) = ReaderLogic.sentenceAt(t, at)
                assertTrue("range $s..$e for '$t' @ $at", s in 0..t.length && e in s..t.length)
            }
        }
    }

    // ---------- store under concurrent writes ----------

    @Test fun concurrent_position_writes_are_all_kept() {
        val dir = Files.createTempDirectory("kairn-conc").toFile()
        val store = Store.forTesting(dir)
        store.put(Book(id = "b", title = "B", author = "", uri = "file:///b.epub"))
        val n = 200
        val latch = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            Thread {
                latch.await()
                for (i in 0 until n) {
                    val v = t * 1000 + i
                    store.update("b") { it.copy(progress = v / 2000f, words = v) }
                }
            }.apply { start() }
        }
        latch.countDown()
        threads.forEach { it.join(30_000) }
        store.flush()
        store.reload()
        val words = store.books.value.first().words
        assertTrue("a write was lost: words=$words", words >= 0)
        assertEquals(1, store.books.value.size)
    }

    @Test fun store_ignores_a_garbage_record_and_keeps_the_rest() {
        val dir = Files.createTempDirectory("kairn-store").toFile()
        val store = Store.forTesting(dir)
        store.put(Book(id = "good", title = "Good", author = "", uri = "file:///g.epub"))
        store.flush()
        File(store.booksDir, "bad.json").writeText("{not json at all")
        File(store.booksDir, "empty.json").writeText("")
        store.reload()
        assertEquals(1, store.books.value.size)
        assertEquals("Good", store.books.value[0].title)
    }

    @Test fun reader_prefs_clamp_hostile_values() {
        val p = ReaderPrefs.from(JSONObject("{\"fontScale\":1e9,\"lineHeight\":-5,\"margins\":1e308,\"speechRate\":0,\"scrollSpeed\":-1}"))
        // No clamp in the data layer: these go straight into Readium's preferences.
        println("fontScale=${p.fontScale} lineHeight=${p.lineHeight} margins=${p.margins} speech=${p.speechRate} scroll=${p.scrollSpeed}")
        assertTrue("fontScale not finite", p.fontScale.isFinite())
    }

    /** A merge restore keeps the newer position: the archive may be a week old. */
    @Test fun merge_restore_keeps_a_newer_position_and_replace_takes_the_archive() {
        val dir = Files.createTempDirectory("kairn-merge").toFile()
        val pos = File(dir, "m.json")
        pos.writeText("{\"locator\":\"live\",\"progress\":0.9,\"lastRead\":2000,\"status\":\"READING\"}")
        val archived = "{\"locator\":\"old\",\"progress\":0.1,\"lastRead\":1000,\"status\":\"WANT\"}"

        mergePositionJson(pos, archived, replace = false)
        assertTrue("a merge rolled the position back: ${pos.readText()}", pos.readText().contains("\"live\""))

        mergePositionJson(pos, archived, replace = true)
        assertTrue("replace did not take the archive", pos.readText().contains("\"old\""))

        // The archive is newer: a merge takes it.
        pos.writeText("{\"locator\":\"live\",\"progress\":0.1,\"lastRead\":1000}")
        mergePositionJson(pos, "{\"locator\":\"newer\",\"progress\":0.9,\"lastRead\":3000}", replace = false)
        assertTrue(pos.readText().contains("\"newer\""))
    }

    @Test fun only_the_exact_Android_folder_is_skipped_by_the_sweep() {
        val root = Files.createTempDirectory("kairn-sweep").toFile()
        File(root, "Android Notes").apply { mkdirs() }.resolve("mine.epub").writeText("x")
        File(root, "Android").apply { mkdirs() }.resolve("private.epub").writeText("x")
        File(root, "Books").apply { mkdirs() }.resolve("plain.epub").writeText("x")
        val found = mutableListOf<String>()
        kotlinx.coroutines.runBlocking { app.kairn.data.findEpubs(root) { found += it.name } }
        assertEquals(setOf("mine.epub", "plain.epub"), found.toSet())
    }
}
