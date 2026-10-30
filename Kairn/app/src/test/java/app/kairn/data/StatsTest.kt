package app.kairn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsTest {

    private val today = LocalDate.of(2026, 10, 30)

    @Test
    fun `minutes land on the day they were read`() {
        val s = ReadingStats().add("2026-10-30", 12).add("2026-10-30", 8)
        assertEquals(20, s.minutesOn("2026-10-30"))
        assertEquals(0, s.minutesOn("2026-10-29"))
    }

    @Test
    fun `a non-positive session is ignored rather than erasing the day`() {
        val s = ReadingStats().add("2026-10-30", 30).add("2026-10-30", 0).add("2026-10-30", -40)
        assertEquals(30, s.minutesOn("2026-10-30"))
    }

    @Test
    fun `the streak counts back over consecutive days`() {
        val s = ReadingStats()
            .add("2026-10-30", 5).add("2026-10-29", 5).add("2026-10-28", 5)
        assertEquals(3, s.streak(today))
    }

    @Test
    fun `a day not read yet does not break the streak`() {
        // Nothing today, but yesterday and the day before: the run is still alive until midnight passes.
        val s = ReadingStats().add("2026-10-29", 5).add("2026-10-28", 5)
        assertEquals(2, s.streak(today))
    }

    @Test
    fun `a missed day ends the streak`() {
        val s = ReadingStats().add("2026-10-30", 5).add("2026-10-28", 5)
        assertEquals(1, s.streak(today))
    }

    @Test
    fun `no reading at all is a zero streak, not one`() {
        assertEquals(0, ReadingStats().streak(today))
    }

    @Test
    fun `the week is seven days oldest first, including empty ones`() {
        val s = ReadingStats().add("2026-10-30", 5)
        val w = s.recent(7, today)
        assertEquals(7, w.size)
        assertEquals(LocalDate.of(2026, 10, 24), w.first().first)
        assertEquals(LocalDate.of(2026, 10, 30), w.last().first)
        assertEquals(5, w.last().second)
        assertEquals(0, w.first().second)
    }

    @Test
    fun `a record survives a JSON round trip`() {
        val s = ReadingStats(mapOf("2026-10-30" to 42), setOf("abc"))
        val back = ReadingStats.from(org.json.JSONObject(s.toJson().toString()))
        assertEquals(s, back)
    }

    @Test
    fun `a zero-day entry on disk is not kept`() {
        val o = org.json.JSONObject("""{"days":{"2026-10-30":0,"2026-10-29":7},"finished":[]}""")
        val s = ReadingStats.from(o)
        assertTrue("2026-10-30" !in s.days)
        assertEquals(7, s.minutesOn("2026-10-29"))
    }

    @Test
    fun `an empty file reads as an empty record instead of failing`() {
        assertEquals(ReadingStats(), ReadingStats.from(null))
    }
}
