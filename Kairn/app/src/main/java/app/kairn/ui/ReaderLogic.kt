package app.kairn.ui

import app.kairn.data.Mark

/**
 * The parts of the reader that are arithmetic rather than Android: how long is left, which marks belong to
 * a chapter, and what a list looks like after a delete.
 */
object ReaderLogic {

    /** The reading speed behind the time-left estimate. */
    const val WPM = 240

    /** A chapter under this is front matter, a divider or a plate: its words-per-position is noise. */
    const val MIN_CHAPTER_WORDS = 250

    /** One position cannot describe a ratio: Readium always splits a real chapter into several. */
    const val MIN_CHAPTER_POSITIONS = 2

    /**
     * The guard band for the INTERIM estimate only. Readium counts a position as 1024 units of
     * number of characters the content service returns, so the real words-per-position is far higher than
     */
    const val MIN_WORDS_PER_POSITION = 40f
    const val MAX_WORDS_PER_POSITION = 1200f

    /** What has been measured so far, book-wide: words and the positions they occupy. */
    data class Pace(val words: Long = 0, val positions: Int = 0) {
        val wordsPerPosition: Float?
            get() = if (positions > 0 && words > 0)
                (words.toFloat() / positions).coerceIn(MIN_WORDS_PER_POSITION, MAX_WORDS_PER_POSITION)
            else null

        /** Adds a chapter's measurement; anything that cannot describe the book is ignored. */
        fun add(words: Int, positions: Int): Pace {
            if (words < MIN_CHAPTER_WORDS || positions < MIN_CHAPTER_POSITIONS) return this
            val ratio = words.toFloat() / positions
            if (ratio < MIN_WORDS_PER_POSITION || ratio > MAX_WORDS_PER_POSITION) return this
            return Pace(this.words + words, this.positions + positions)
        }
    }

    /**
     * Whole-book minutes left, at [wpm], from the accumulated [pace].
     * Readium splits a resource into positions of even character size, so positions track length closely;
     * words-per-position for the book turns the remaining positions into remaining words.
     */
    fun minutesLeft(pace: Pace, totalPositions: Int, bookProgression: Float, wpm: Int = WPM): Int? {
        val perPosition = pace.wordsPerPosition ?: return null
        if (totalPositions <= 0) return null
        val remaining = ((1f - bookProgression.coerceIn(0f, 1f)) * totalPositions * perPosition).toInt()
        return remaining / wpm
    }

    /**
     * Exact minutes left from the book's own word count, counted once in the background.
     * No ratio, no extrapolation: remaining words are the book's words times what is left of it.
     */
    fun minutesLeftInBook(bookWords: Int, bookProgression: Float, wpm: Int = WPM): Int? {
        if (bookWords <= 0) return null
        val remaining = ((1f - bookProgression.coerceIn(0f, 1f)) * bookWords).toInt()
        return remaining / wpm
    }

    /** English runs ~6 bytes per word in UTF-8 including the space. Only used for the instant estimate. */
    const val BYTES_PER_WORD = 6.0

    /**
     * Words in one chapter's raw XHTML, without parsing it: one pass over the bytes, counting runs of
     * non-whitespace outside <...>. Text nodes are what carry the words, so attributes and tags are skipped.
     * Parsing a chapter costs a Content model; this costs a scan, which is why the whole book can be counted.
     */
    fun countWords(bytes: ByteArray): Int {
        var words = 0
        var inTag = false
        var inWord = false
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (inTag) {
                if (c == '>'.code) inTag = false
                inWord = false
                continue
            }
            if (c == '<'.code) { inTag = true; inWord = false; continue }
            val space = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code || c == 0x0C
            if (space) inWord = false else if (!inWord) { inWord = true; words++ }
        }
        return words
    }

    /** Words from a book's total chapter size, for a label that appears immediately. */
    fun estimateWords(bytes: Long): Int = if (bytes <= 0) 0 else (bytes / BYTES_PER_WORD).toInt()

    /** Marks minus one id. Used by every delete, so the "already gone" case is the same everywhere. */
    fun withoutMark(marks: List<Mark>, id: String): List<Mark> = marks.filterNot { it.id == id }

    /** True when any bookmark in [marks] sits in [href]. One bookmark per chapter is decided by this. */
    fun chapterHasBookmark(marks: List<Mark>, href: String, hrefOf: (Mark) -> String?): Boolean =
        marks.any { it.kind == "bookmark" && hrefOf(it) == href }
}
