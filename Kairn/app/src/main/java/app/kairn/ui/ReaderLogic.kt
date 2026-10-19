package app.kairn.ui

import app.kairn.data.Mark

/**
 * The parts of the reader that are arithmetic rather than Android: how long is left, which marks belong to
 * a chapter, and what a list looks like after a delete. Kept out of ReaderActivity so they can be tested
 * without a device, and so the Activity holds less.
 */
object ReaderLogic {

    /** The reading speed behind the time-left estimate. */
    const val WPM = 240

    /**
     * Whole-book minutes left, at [wpm].
     *
     * Readium splits a resource into positions of even character size, so positions-per-chapter is
     * proportional to chapter length. Measuring words-per-position on the chapter being read scales to the
     * rest of the book. Null when any input is missing - the caller then shows nothing rather than a guess.
     */
    fun minutesLeft(chapterWords: Int, chapterPositions: Int, totalPositions: Int, bookProgression: Float, wpm: Int = WPM): Int? {
        if (chapterWords <= 0 || chapterPositions <= 0 || totalPositions <= 0) return null
        val wordsPerPosition = chapterWords.toFloat() / chapterPositions
        val remaining = ((1f - bookProgression.coerceIn(0f, 1f)) * totalPositions * wordsPerPosition).toInt()
        return remaining / wpm
    }

    /** The label under the progress slider. "" when the estimate cannot be known yet. */
    fun timeLeftLabel(chapterWords: Int, chapterPositions: Int, totalPositions: Int, bookProgression: Float): String {
        val mins = minutesLeft(chapterWords, chapterPositions, totalPositions, bookProgression) ?: return ""
        return when {
            mins <= 0 -> "under a min left"
            mins < 60 -> "$mins min left"
            else -> "${mins / 60} h ${mins % 60} min left"
        }
    }

    /** Marks minus one id. Used by every delete, so the "already gone" case is the same everywhere. */
    fun withoutMark(marks: List<Mark>, id: String): List<Mark> = marks.filterNot { it.id == id }

    /** True when any bookmark in [marks] sits in [href]. One bookmark per chapter is decided by this. */
    fun chapterHasBookmark(marks: List<Mark>, href: String, hrefOf: (Mark) -> String?): Boolean =
        marks.any { it.kind == "bookmark" && hrefOf(it) == href }
}
