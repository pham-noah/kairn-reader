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

    /** A chapter shorter than this is a title page or a divider: its words-per-position is noise. */
    const val MIN_CHAPTER_WORDS = 100

    /**
     * What has been measured so far, book-wide: words and the positions they occupy.
     */
    data class Pace(val words: Long = 0, val positions: Int = 0) {
        val wordsPerPosition: Float? get() = if (positions > 0 && words > 0) words.toFloat() / positions else null

        /** Adds a chapter's measurement; a chapter under [MIN_CHAPTER_WORDS] is ignored as noise. */
        fun add(words: Int, positions: Int): Pace =
            if (words < MIN_CHAPTER_WORDS || positions <= 0) this
            else Pace(this.words + words, this.positions + positions)
    }

    /**
     * Whole-book minutes left, at [wpm], from the accumulated [pace].
     * Readium splits a resource into positions of even character size, so positions track length closely; words-per-position for the book turns the remaining positions into remaining words.
     */
    fun minutesLeft(pace: Pace, totalPositions: Int, bookProgression: Float, wpm: Int = WPM): Int? {
        val perPosition = pace.wordsPerPosition ?: return null
        if (totalPositions <= 0) return null
        val remaining = ((1f - bookProgression.coerceIn(0f, 1f)) * totalPositions * perPosition).toInt()
        return remaining / wpm
    }

    /** Marks minus one id. Used by every delete, so the "already gone" case is the same everywhere. */
    fun withoutMark(marks: List<Mark>, id: String): List<Mark> = marks.filterNot { it.id == id }

    /** True when any bookmark in [marks] sits in [href]. One bookmark per chapter is decided by this. */
    fun chapterHasBookmark(marks: List<Mark>, href: String, hrefOf: (Mark) -> String?): Boolean =
        marks.any { it.kind == "bookmark" && hrefOf(it) == href }
}
