package app.kairn.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A cast is one small file per book: it must survive a restart, a bad file, and a hostile id. */
class BookCastTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store() = Store.forTesting(tmp.root).also { runBlocking { it.load() } }

    @Test fun a_cast_round_trips_through_its_file() {
        val s = store()
        val cast = BookCast(enabled = true, voices = mapOf("Ada" to 2, "Bram" to 4))
        s.saveCast("abc123", cast)
        s.flush()
        val back = s.loadCast("abc123")
        assertEquals(cast, back)
        assertTrue(File(s.castDir, "abc123.json").exists())
    }

    @Test fun a_book_with_no_cast_file_reads_as_the_default() {
        val s = store()
        assertEquals(BookCast(), s.loadCast("nothing"))
    }

    @Test fun a_broken_cast_file_is_one_voice_not_a_crash() {
        val s = store()
        File(s.castDir, "abc.json").writeText("{not json")
        assertEquals(BookCast(), s.loadCast("abc"))
        File(s.castDir, "def.json").writeText("""{"enabled":false,"voices":{"Ada":9,"Bram":"x"}}""")
        val c = s.loadCast("def")
        assertFalse(c.enabled)
        // Slot 9 does not exist and a string is not a slot: both are dropped rather than clamped to a voice.
        assertTrue(c.voices.isEmpty())
    }

    @Test fun an_id_that_is_a_file_name_is_enforced() {
        val s = store()
        s.saveCast("../../escape", BookCast(enabled = false))
        assertTrue(File(s.root.parentFile, "escape.json").exists().not())
        assertEquals(BookCast(), s.loadCast("../../escape"))
    }

    @Test fun a_slot_is_clamped_to_the_voices_the_engine_can_have() {
        val cast = BookCast().withVoice("Ada", 99)
        assertEquals(BookCast.SLOTS, cast.slotOf("Ada"))
        assertEquals(0, BookCast().slotOf("Nobody"))
    }
}
