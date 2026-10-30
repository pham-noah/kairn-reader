package app.kairn.data

import org.json.JSONObject
import java.time.LocalDate

/**
 * Minutes read per day (DS-02), in plain JSON next to the books. A day is a local calendar date: "today" is
 * what a reader means, not a UTC day.
 */
data class ReadingStats(
    /** `yyyy-MM-dd` -> minutes read that day. Days with no reading are simply absent. */
    val days: Map<String, Int> = emptyMap(),
    /** Book ids already counted as finished, so finishing the same book twice is not two books. */
    val finished: Set<String> = emptySet(),
) {
    fun minutesOn(day: String): Int = days[day] ?: 0

    fun minutesToday(today: LocalDate = LocalDate.now()): Int = minutesOn(today.toString())

    fun totalMinutes(): Int = days.values.sum()

    /** Adds [minutes] to [day]. Non-positive input is ignored, so a stray call cannot erase a day. */
    fun add(day: String, minutes: Int): ReadingStats {
        if (minutes <= 0) return this
        return copy(days = days + (day to minutesOn(day) + minutes))
    }

    fun markFinished(id: String): ReadingStats = copy(finished = finished + id)

    /** The last [n] days, oldest first, including days with nothing read. */
    fun recent(n: Int = 7, today: LocalDate = LocalDate.now()): List<Pair<LocalDate, Int>> =
        (n - 1 downTo 0).map { back -> today.minusDays(back.toLong()) }.map { d -> d to minutesOn(d.toString()) }

    /**
     * Consecutive days with reading. A day that has not been read *yet* does not break the run, so the
     * streak does not collapse every midnight before the reader opens the app.
     */
    fun streak(today: LocalDate = LocalDate.now()): Int {
        var day = if (minutesOn(today.toString()) > 0) today else today.minusDays(1)
        var run = 0
        while (minutesOn(day.toString()) > 0) {
            run++
            day = day.minusDays(1)
        }
        return run
    }

    fun toJson() = JSONObject()
        .put("v", 1)
        .put("days", JSONObject(days as Map<*, *>))
        .put("finished", org.json.JSONArray(finished.toList()))

    companion object {
        fun from(o: JSONObject?): ReadingStats {
            if (o == null) return ReadingStats()
            val days = HashMap<String, Int>()
            o.optJSONObject("days")?.let { d ->
                for (key in d.keys()) {
                    val v = d.optInt(key, 0)
                    if (v > 0) days[key] = v
                }
            }
            val finished = o.optJSONArray("finished")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }.toSet()
            } ?: emptySet()
            return ReadingStats(days, finished)
        }
    }
}
