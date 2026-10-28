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

    /** Kept here for the reader's own call sites; the counting itself lives in the data layer. */
    fun countWords(bytes: ByteArray): Int = app.kairn.data.Words.count(bytes)

    /** Minutes left in the chapter on screen: its own word count, times what is left of it. */
    fun minutesLeftInChapter(chapterWords: Int, chapterProgression: Float, wpm: Int = WPM): Int? {
        if (chapterWords <= 0) return null
        val remaining = ((1f - chapterProgression.coerceIn(0f, 1f)) * chapterWords).toInt()
        return remaining / wpm
    }

    /** TTS engines take about 4000 characters per utterance; chunk well under that. */
    const val TTS_CHUNK = 3000

    /** Sentences packed into utterances no longer than [TTS_CHUNK]. Nothing is dropped. */
    fun ttsChunks(text: String): List<String> {
        val clean = text.replace(Regex("[ \t]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val sentences = clean.split(Regex("(?<=[.!?\u2026])\\s+|\\n+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (sentence in sentences) {
            var piece = sentence
            while (piece.length > TTS_CHUNK) {                 // one monstrous "sentence"
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                out += piece.take(TTS_CHUNK)
                piece = piece.drop(TTS_CHUNK)
            }
            if (sb.length + piece.length + 1 > TTS_CHUNK) { out += sb.toString(); sb.clear() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(piece)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Where in the chapter to start speaking: the reader's progression, snapped forward to a sentence. */
    fun ttsStartOffset(text: String, progression: Float): Int {
        if (text.isEmpty()) return 0
        val rough = (progression.coerceIn(0f, 1f) * text.length).toInt().coerceIn(0, text.length - 1)
        if (rough == 0) return 0
        val end = Regex("[.!?\u2026]\\s").find(text, rough)?.range?.last ?: return rough
        return (end + 1).coerceIn(0, text.length - 1)
    }

    /** Marks minus one id. Used by every delete, so the "already gone" case is the same everywhere. */
    fun withoutMark(marks: List<Mark>, id: String): List<Mark> = marks.filterNot { it.id == id }

    /** True when any bookmark in [marks] sits in [href]. One bookmark per chapter is decided by this. */
    fun chapterHasBookmark(marks: List<Mark>, href: String, hrefOf: (Mark) -> String?): Boolean =
        marks.any { it.kind == "bookmark" && hrefOf(it) == href }
}
