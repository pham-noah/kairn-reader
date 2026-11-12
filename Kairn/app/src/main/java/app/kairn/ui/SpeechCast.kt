package app.kairn.ui

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/**
 * Who is speaking, from the text alone.
 *
 * The attribution problem is not the audio: it is deciding that a quoted line belongs to Frodo and not to
 * Sam. Everything here is a heuristic with one rule that outranks the rest - **a wrong voice is worse than a
 * flat one**, so anything that cannot be attributed reads in the narrator's voice and is never guessed twice.
 *
 * A span covers the whole text: the narrator owns everything that is not inside quotes, and each quoted span
 * is labelled with the name the sentence attributes it to - or with "" when it does not say.
 */
object SpeechCast {

    @Immutable
    data class Span(val start: Int, val end: Int, val speaker: String)

    /** Beyond this many, a cast stops being a cast and becomes noise; the rest stays narrator. */
    const val MAX_SPEAKERS = 6

    /**
     * A chapter with more than this many quoted spans is a script, not prose - and a thousand voice changes
     * is a thousand utterances queued at once, which is how a reader becomes a stutter. Such a chapter is
     * read in one voice, deliberately.
     */
    const val MAX_SPANS = 1_000

    /** Verbs that carry an attribution. Deliberately short: a long list starts matching narration. */
    private val VERBS = Regex(
        "\\b(said|says|say|asked|asks|replied|replies|answered|answers|cried|cries|whispered|whispers|" +
            "shouted|shouts|murmured|murmurs|muttered|mutter|called|calls|added|adds|told|tells|exclaimed|" +
            "exclaims|repeated|repeats|snapped|snaps|growled|growls|sighed|sighs|laughed|laughs|hissed|" +
            "observed|remarked|demanded|insisted|agreed|admitted|explained|announced|began|continued)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Capitalised words that are never a character: pronouns, articles, and the words a sentence starts on. */
    private val NOT_A_NAME = setOf(
        "he", "she", "they", "it", "i", "we", "you", "the", "a", "an", "and", "but", "so", "then", "there",
        "this", "that", "these", "those", "his", "her", "their", "its", "my", "your", "our", "what", "who",
        "why", "how", "when", "where", "if", "no", "yes", "oh", "ah", "well", "but", "not", "do", "did",
        "does", "is", "was", "were", "are", "be", "been", "have", "has", "had", "will", "would", "could",
        "should", "can", "may", "might", "must", "one", "all", "some", "any", "nothing", "something",
        "everyone", "someone", "nobody", "please", "thank", "thanks", "sir", "madam", "mister", "mrs",
        "mr", "ms", "dr", "god", "lord", "good", "great", "come", "look", "listen", "get", "let", "now",
    )

    /** A capitalised word, up to three of them in a row: "Frodo", "Mr. Darcy", "Ursula K. Le Guin". */
    private val NAME = Regex("\\b(\\p{Lu}[\\p{L}'’\\-]{1,20}(?:\\s+\\p{Lu}[\\p{L}'’\\-]{1,20}){0,2})\\b")

    /** How far from the quote an attribution may sit. Beyond this the sentence has moved on. */
    private const val WINDOW = 200

    /**
     * The whole text, split by speaker. Adjacent spans with the same speaker are merged, so a chapter with no
     * dialogue is one narrator span and the caller can skip the cast entirely.
     */
    fun attribute(text: String): List<Span> {
        if (text.isBlank()) return emptyList()
        val quotes = quotedSpans(text)
        if (quotes.isEmpty()) return listOf(Span(0, text.length, ""))
        if (quotes.size > MAX_SPANS) return listOf(Span(0, text.length, ""))

        // The verbs and the names live OUTSIDE the quotes. Searching the raw text found "Halt!", "Run" and
        // "First" as characters, because the first capitalised word of a spoken line is capitalised too.
        val masked = maskQuotes(text, quotes)
        val speakers = ArrayList<String>()          // cast, in order of first appearance
        val counts = HashMap<String, Int>()
        var lastSpeaker: String? = null
        val labelled = ArrayList<Span>(quotes.size)

        for ((qs, qe) in quotes) {
            val name = attributeQuote(masked, qs, qe, speakers) ?: run {
                // Two voices and an unattributed line: in a dialogue the speaker alternates, and that is the
                // one inference the text itself supports. With three or more it guesses, so it does not.
                if (speakers.size == 2) speakers.firstOrNull { it != lastSpeaker } else null
            }
            val speaker = name.orEmpty()
            val key = canonical(speaker, speakers)
            if (key.isNotEmpty()) {
                if (key !in speakers) speakers += key
                counts[key] = (counts[key] ?: 0) + 1
                lastSpeaker = key
            }
            labelled += Span(qs, qe, key)
        }

        // A cast of twenty is a narrator with a few voices in it. Keep the ones that actually speak.
        val keep = counts.entries.sortedByDescending { it.value }.take(MAX_SPEAKERS).map { it.key }.toSet()
        val merged = ArrayList<Span>(labelled.size + 1)
        var cursor = 0
        fun add(from: Int, to: Int, who: String) {
            if (to <= from) return
            if (merged.isNotEmpty() && merged.last().speaker == who && merged.last().end == from) {
                merged[merged.size - 1] = merged.last().copy(end = to)
            } else merged += Span(from, to, who)
        }
        for (s in labelled) {
            add(cursor, s.start, "")
            add(s.start, s.end, if (s.speaker in keep) s.speaker else "")
            cursor = s.end
        }
        add(cursor, text.length, "")
        return merged
    }

    /**
     * One spelling per character. "FRODO said" and "Frodo said" are the same person, and giving them two
     * voices is the most visible way a cast can be wrong.
     */
    private fun canonical(name: String, known: List<String>): String {
        if (name.isEmpty()) return ""
        return known.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
    }

    /** The cast, in the order it first speaks. Never includes the narrator. */
    fun speakers(spans: List<Span>): List<String> {
        val out = LinkedHashSet<String>()
        spans.forEach { if (it.speaker.isNotEmpty()) out += it.speaker }
        return out.toList()
    }

    /** The speaker of the span containing [at]; "" for the narrator. */
    fun speakerAt(spans: List<Span>, at: Int): String =
        spans.lastOrNull { at >= it.start && at < it.end }?.speaker.orEmpty()

    private const val SENTENCE_STOPS = ".!?\u2026\n"

    private fun sentenceStart(text: String, at: Int): Int {
        var i = at.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        while (i > 0 && text[i - 1] !in SENTENCE_STOPS) i--
        return i
    }

    private fun sentenceEnd(text: String, at: Int): Int {
        var i = at.coerceIn(0, text.length)
        while (i < text.length && text[i] !in SENTENCE_STOPS) i++
        return (i + 1).coerceAtMost(text.length)
    }

    /** Where the sentence after the one holding [at] ends, so an attribution can follow a quote. */
    private fun nextSentenceEnd(text: String, at: Int): Int {
        val first = sentenceEnd(text, at)
        if (first >= text.length) return text.length
        // Skip the terminator and any closing quote/space, then find the end of that sentence.
        var i = first
        while (i < text.length && text[i] in " \t\n") i++
        return sentenceEnd(text, i)
    }

    /** [text] with every quoted region blanked out, same length and therefore same offsets. */
    private fun maskQuotes(text: String, quotes: List<Pair<Int, Int>>): String {
        if (quotes.isEmpty()) return text
        val chars = text.toCharArray()
        for ((s, e) in quotes) {
            for (i in (s - 1).coerceAtLeast(0)..(e).coerceAtMost(chars.size - 1)) chars[i] = ' '
        }
        return String(chars)
    }

    /** Quoted regions, in reading order. Curly quotes pair by their own character; straight quotes toggle. */
    private fun quotedSpans(text: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val pairs = mapOf('“' to '”', '«' to '»')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val close = pairs[c] ?: if (c == '"') '"' else null
            if (close == null) { i++; continue }
            var j = i + 1
            while (j < text.length && text[j] != close) j++
            if (j >= text.length) break
            // An empty pair ("") is not dialogue.
            if (j > i + 1) out += (i + 1) to j
            i = j + 1
        }
        return out
    }

    /**
     * The name in the sentence that says who spoke, or null. [known] is the cast so far: a name the chapter
     * has already used is preferred over a new capitalised word, because "Well" and "The" are not characters.
     */
    private fun attributeQuote(text: String, start: Int, end: Int, known: List<String>): String? {
        // The quote's own sentence and the one after it, and no further: without this a line with no
        // attribution inherits the name of the line before it, which is exactly the wrong voice.
        val from = sentenceStart(text, start)
        val to = sentenceEnd(text, nextSentenceEnd(text, end))
        val context = text.substring(from, to)
        val verb = VERBS.findAll(context)
            .map { it.range.first }
            .minByOrNull { abs(it + from - start) } ?: return null

        // The name sits beside the verb: "\"Hello,\" said Frodo" or "Frodo said, \"Hello\"". The NEAREST
        // capitalised word wins, and a name the chapter already knows only breaks a tie - "said Sam" after
        // "said Frodo" must not be read as Frodo again just because Frodo is the familiar one.
        val near = (verb - 48).coerceAtLeast(0) to (verb + 48).coerceAtMost(context.length)
        val window = context.substring(near.first, near.second)
        val knownLower = known.associateBy { it.lowercase() }
        val best = NAME.findAll(window)
            .map { it.groupValues[1].trim() to (it.range.first + near.first) }
            .filter { (name, _) -> name.lowercase() !in NOT_A_NAME }
            .minWithOrNull(
                compareBy({ abs(it.second - verb) }, { if (it.first.lowercase() in knownLower) 0 else 1 })
            ) ?: return null
        return knownLower[best.first.lowercase()] ?: best.first
    }
}
