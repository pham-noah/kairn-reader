package app.kairn.ui

import app.kairn.data.Mark

/**
 * The parts of the reader that are arithmetic rather than Android: how long is left, which marks belong to
 * a chapter, and what a list looks like after a delete.
 */
object ReaderLogic {

    /** Saving on every locator would write the disk on every page turn; this is the floor between saves. */
    const val SAVE_INTERVAL_MS = 5_000L

    /** A page turn inside the floor waits this long, so a run of turns costs one write. */
    const val SAVE_DEBOUNCE_MS = 800L

    /** No wait if the floor has passed, otherwise the debounce. */
    fun saveDelay(now: Long, lastSaved: Long): Long =
        if (now - lastSaved >= SAVE_INTERVAL_MS) 0 else SAVE_DEBOUNCE_MS

    /**
     * A table-of-contents entry reduced to what the title map needs. Readium's Link is unusable in a JVM
     * test (Href parses through android.net.Uri), so the walk takes this instead of a Link.
     */
    class TocNode(val href: String, val title: String?, val children: List<TocNode> = emptyList())

    /**
     * href -> chapter title, from the table of contents, so scrubbing can name the chapter. A TOC href
     * carries its folder (OEBPS/ch03.xhtml) while a locator often carries only ch03.xhtml, so key both.
     * The readingOrder is a flat spine: it fills chapters the TOC skipped and never overwrites one.
     */
    fun chapterTitles(toc: List<TocNode>, readingOrder: List<TocNode>): Map<String, String> {
        val map = HashMap<String, String>()
        // Iterative and bounded: a table of contents from an untrusted file can nest deeper than the JVM stack.
        val stack = ArrayDeque<Pair<TocNode, Int>>()
        toc.asReversed().forEach { stack.addLast(it to 1) }
        while (stack.isNotEmpty()) {
            val (l, depth) = stack.removeLast()
            l.title?.takeIf { it.isNotBlank() }?.let { title ->
                val href = l.href.toString()
                map[href] = title
                map[href.substringAfterLast('/')] = title
            }
            if (depth < MAX_TOC_DEPTH) l.children.asReversed().forEach { stack.addLast(it to depth + 1) }
        }
        readingOrder.forEach { l ->
            l.title?.takeIf { it.isNotBlank() }?.let { map.putIfAbsent(l.href.toString(), it) }
        }
        return map
    }

    /** A real contents list is a handful of levels; this is only a wall for a crafted one. */
    private const val MAX_TOC_DEPTH = 64

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
        if (totalPositions <= 0 || wpm <= 0) return null
        val remaining = ((1f - bookProgression.coerceIn(0f, 1f)) * totalPositions * perPosition).toInt()
        return remaining / wpm
    }

    /**
     * Exact minutes left from the book's own word count, counted once in the background.
     * No ratio, no extrapolation: remaining words are the book's words times what is left of it.
     */
    fun minutesLeftInBook(bookWords: Int, bookProgression: Float, wpm: Int = WPM): Int? {
        if (bookWords <= 0 || wpm <= 0) return null
        val remaining = ((1f - bookProgression.coerceIn(0f, 1f)) * bookWords).toInt()
        return remaining / wpm
    }

    /** Kept here for the reader's own call sites; the counting itself lives in the data layer. */
    fun countWords(bytes: ByteArray): Int = app.kairn.data.Words.count(bytes)

    /** Minutes left in the chapter on screen: its own word count, times what is left of it. */
    fun minutesLeftInChapter(chapterWords: Int, chapterProgression: Float, wpm: Int = WPM): Int? {
        if (chapterWords <= 0 || wpm <= 0) return null
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
        val end = Regex("[.!?\\u2026]\\s").find(text, rough)?.range?.last ?: return rough
        return (end + 1).coerceIn(0, text.length - 1)
    }

    /**
     * A chunk to speak, and where it starts in the chapter text, so a spoken word maps back to it.
     * [speaker] is "" for the narrator and a name for a quoted line; a chunk never mixes two speakers.
     */
    data class TtsPiece(val start: Int, val text: String, val speaker: String = "")

    private val sentenceEnd = Regex("[.!?\\u2026][\"')\\]]*\\s+|\\n{1,}")

    /**
     * Like [ttsChunks], but each chunk carries its offset in the ORIGINAL text.
     *
     * With [spans] the chunks also break on a change of speaker. That is what makes a cast audible: one
     * utterance carries one voice, and the engine switches between utterances, never inside one.
     */
    fun ttsPieces(text: String, spans: List<SpeechCast.Span> = emptyList()): List<TtsPiece> {
        if (spans.isEmpty()) return chunk(text, 0, text.length, "")
        // A piece is cut by the speaker, not by the sentence: the boundary that matters to the engine is the
        // one where the voice changes, and a sentence can contain two of those.
        val out = ArrayList<TtsPiece>(spans.size)
        for (s in spans) out += chunk(text, s.start.coerceIn(0, text.length), s.end.coerceIn(0, text.length), s.speaker)
        return out
    }

    /** One speaker's [from]..[to] of [text], packed into utterances no longer than [TTS_CHUNK]. */
    private fun chunk(text: String, from: Int, to: Int, speaker: String): List<TtsPiece> {
        val out = ArrayList<TtsPiece>()
        var cur: StringBuilder? = null
        var curStart = 0
        fun flush() {
            cur?.let { if (it.isNotEmpty()) out += TtsPiece(curStart, it.toString(), speaker) }
            cur = null
        }
        var start = from
        while (start < to) {
            val m = sentenceEnd.find(text, start)
            val end = when {
                m == null -> to
                m.range.last + 1 > to -> to
                else -> m.range.last + 1
            }
            val raw = text.substring(start, end)
            val body = raw.trim()
            start = end
            if (body.isEmpty()) continue
            val lead = start - raw.length + (raw.length - raw.trimStart().length)
            var piece = body
            var at = lead
            while (piece.length > TTS_CHUNK) {
                flush()
                out += TtsPiece(at, piece.take(TTS_CHUNK), speaker)
                piece = piece.drop(TTS_CHUNK); at += TTS_CHUNK
            }
            var c = cur
            if (c == null) { c = StringBuilder(); cur = c; curStart = at }
            else if (c.length + piece.length + 1 > TTS_CHUNK) {
                flush(); c = StringBuilder(); cur = c; curStart = at
            }
            if (c.isNotEmpty()) c.append(' ')
            c.append(piece)
        }
        flush()
        return out
    }

    /** The sentence around [at], as a start/end pair inside [text]. */
    fun sentenceAt(text: String, at: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val pos = at.coerceIn(0, text.length - 1)
        val stops = ".!?\u2026\n"
        var s = pos
        while (s > 0 && stops.indexOf(text[s - 1]) < 0) s--
        var e = pos
        while (e < text.length - 1 && stops.indexOf(text[e]) < 0) e++
        e = (e + 1).coerceAtMost(text.length)
        if (e - s > 180) { s = (pos - 60).coerceAtLeast(0); e = (s + 180).coerceAtMost(text.length) }
        return s to e
    }

    /** Marks minus one id. Used by every delete, so the "already gone" case is the same everywhere. */
    fun withoutMark(marks: List<Mark>, id: String): List<Mark> = marks.filterNot { it.id == id }

    /** True when any bookmark in [marks] sits in [href]. One bookmark per chapter is decided by this. */
    fun chapterHasBookmark(marks: List<Mark>, href: String, hrefOf: (Mark) -> String?): Boolean =
        marks.any { it.kind == "bookmark" && hrefOf(it) == href }

    /**
     * Last path segment with any fragment dropped. A TOC href is relative to its own entry ("ch03.xhtml")
     * while a locator href is not ("OEBPS/ch03.xhtml"), so the two never compare equal as strings.
     */
    fun chapterKey(href: String?): String? =
        href?.substringBefore('#')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
}
