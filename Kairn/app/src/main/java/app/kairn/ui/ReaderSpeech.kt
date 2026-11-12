package app.kairn.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.kairn.R
import app.kairn.data.BookCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.content

/**
 * Read aloud, with the page following the voice.
 *
 * Tapping play speaks from the reader's own position; when a chapter runs out the page turns and the next one
 * starts, which is also what happens if the reader turns it themselves. The voice reports each word's place in
 * the chapter, and that is turned into a painted sentence - and, in page mode, a page turn, because a page
 * cannot scroll to follow a voice the way a scroll view can.
 *
 * The Activity keeps the two things that need it: the sleep timer and the foreground service that keeps the
 * process alive while the screen is off.
 */
internal class ReaderSpeech(
    private val context: Context,
    private val ui: ReaderUiState,
    private val navigator: () -> EpubNavigatorFragment?,
    private val publication: () -> Publication?,
    /** The measured positions per chapter: the page to turn to is the last one at or before the voice. */
    private val positionsByHref: () -> Map<String, List<Locator>>,
    private val scope: CoroutineScope,
    /** A message for the reader: a chapter that cannot be read, or a voice that will not start. */
    private val toast: (Int) -> Unit,
    /** True when a timer set to "end of chapter" consumed this chapter's end. */
    private val endOfChapterTimer: () -> Boolean,
    /** A manual stop ends the timer too - a chip must not promise to stop a voice that is already stopped. */
    private val cancelSleepTimer: () -> Unit,
    /** This book's cast, as the reader arranged it. Read on every speak, so a change is never stale. */
    private val cast: () -> BookCast = { BookCast() },
    /** The chapter's cast, for the sheet that lets the reader correct it. */
    private val onSpeakers: (List<String>) -> Unit = {},
) {
    private val engine = ReaderTts(
        context,
        onStarted = { ui.speech.starting = false },
        onEnded = { ui.speech.starting = false; advance() },
        onFailed = {
            ui.speech.on = false; ui.speech.starting = false
            toast(R.string.s_tts_unavailable)
        },
        onWord = ::follow,
    )

    // ---- follow the voice ----
    private var spokenText = ""
    private var spokenHref: String? = null
    private var lastSentence = -1
    private var followedPage = -1
    private var followAt = 0L

    /**
     * The current chapter's text, extracted ahead of the tap. Parsing a chapter through Readium's content
     * model is the only part of "read aloud" that costs real milliseconds on a fat chapter, and it depends on
     * the chapter alone - so it is paid once, when the chapter is opened, instead of on the tap.
     */
    @Volatile private var cachedHref: String? = null
    @Volatile private var cachedText: String? = null
    /** Who speaks, per chapter, computed with the text and thrown away with it. */
    @Volatile private var cachedSpans: List<SpeechCast.Span> = emptyList()

    /** Extract [loc]'s chapter in the background so a tap on Read aloud is only a tap. */
    fun preload(loc: Locator) = scope.launch {
        val p = publication() ?: return@launch
        val href = loc.href.toString()
        if (href == cachedHref) return@launch
        val at = SystemClock.elapsedRealtime()
        val text = withContext(Dispatchers.IO) { runCatching { p.content(loc)?.text() }.getOrNull() }
        if (text.isNullOrBlank()) return@launch
        // Attribution is O(text) with a handful of regex scans: paid once per chapter, never on the tap.
        val spansAt = SystemClock.elapsedRealtime()
        val spans = withContext(Dispatchers.IO) { SpeechCast.attribute(text) }
        cachedSpans = spans
        cachedText = text
        cachedHref = href
        onSpeakers(SpeechCast.speakers(spans))
        Log.i(
            "KairnPerf",
            "tts preload ${text.length} chars ${SystemClock.elapsedRealtime() - at}ms " +
                "cast=${SpeechCast.speakers(spans).size} in ${SystemClock.elapsedRealtime() - spansAt}ms",
        )
    }

    fun toggle() {
        when {
            ui.speech.on && ui.speech.paused -> { ui.speech.paused = false; ui.speech.starting = false; engine.resume() }
            ui.speech.on -> { ui.speech.paused = true; engine.pause() }
            else -> {
                val loc = navigator()?.currentLocator?.value ?: return
                // Show the stop state on the tap, not when the engine finally speaks: a cold bind is seconds
                // long and an unchanged button reads as a dead tap.
                ui.speech.on = true; ui.speech.starting = true; ui.speech.paused = false
                speak(loc)
            }
        }
    }

    /** Stop everything, including the foreground service and any sleep timer. */
    fun stop() {
        engine.stop()
        SpeechService.stop(context)
        ui.speech.on = false; ui.speech.starting = false; ui.speech.paused = false
        ui.sheet = ReaderSheet.NONE
        clearFollow()
        cancelSleepTimer()
    }

    /** Stop the voice but leave the service and the timer alone: the sleep timer already decided about both. */
    fun stopVoice() {
        engine.stop()
        ui.speech.on = false; ui.speech.starting = false; ui.speech.paused = false
        clearFollow()
    }

    fun setRate(rate: Float) = engine.setRate(rate)

    /** A cast change while it speaks: same chapter, same word, new voices from here on. */
    fun setCast() {
        if (!ui.speech.on || ui.speech.paused) return
        val spans = cachedSpans
        // The pieces already queued carry their speakers; only the slots changed, and they are re-queued
        // from the word being spoken.
        engine.setCast(CastPlan(on = cast().enabled, spans = spans, slots = planFor(spans).slots))
    }

    /**
     * Speaker -> voice slot. The reader's own choices win; everyone else is handed slots 1..4 in the order
     * they first speak, so a chapter of six characters does not put three of them on the same voice when
     * four are available.
     */
    private fun planFor(spans: List<SpeechCast.Span>): CastPlan {
        val c = cast()
        if (!c.enabled) return CastPlan.NONE
        val names = SpeechCast.speakers(spans)
        if (names.isEmpty()) return CastPlan.NONE
        val slots = HashMap<String, Int>()
        names.forEachIndexed { i, name ->
            slots[name] = c.voices[name] ?: (1 + i % BookCast.SLOTS)
        }
        return CastPlan(on = true, spans = spans, slots = slots)
    }

    /** [spans] moved to start at [from], because the engine is given the chapter from the reader's word. */
    private fun shifted(spans: List<SpeechCast.Span>, from: Int): List<SpeechCast.Span> =
        spans.mapNotNull { s ->
            val a = (s.start - from).coerceAtLeast(0)
            val b = (s.end - from).coerceAtLeast(0)
            if (b <= a) null else s.copy(start = a, end = b)
        }

    /** The chapter's cast, for the sheet. Empty until a chapter has been read or preloaded. */
    fun speakers(): List<String> = SpeechCast.speakers(cachedSpans)

    /** Warm the engine while the book is opening: a cold bind is seconds long. */
    fun warm() = engine.warm()

    /** Speak from [loc] - the reader turned a page while the voice was on. */
    fun speakFrom(loc: Locator) = speak(loc)

    /** Drop the painted sentence and the page the voice had reached. */
    fun clearFollow() {
        lastSentence = -1
        followedPage = -1
        scope.launch { navigator()?.evaluateJavascript(ReaderFollow.clear()) }
    }

    fun shutdown() = engine.shutdown()

    private fun speak(loc: Locator) = scope.launch {
        val p = publication() ?: return@launch
        val at = SystemClock.elapsedRealtime()
        val href = loc.href.toString()
        val cached = if (href == cachedHref) cachedText else null
        val text = cached ?: withContext(Dispatchers.IO) { runCatching { p.content(loc)?.text() }.getOrNull() }
        if (text.isNullOrBlank()) {
            ui.speech.on = false; ui.speech.starting = false
            toast(R.string.s_tts_chapter_failed)
            return@launch
        }
        val from = ReaderLogic.ttsStartOffset(text, loc.locations.progression?.toFloat() ?: 0f)
        val spans = if (text == cachedText) cachedSpans else withContext(Dispatchers.IO) { SpeechCast.attribute(text) }
        cachedSpans = spans
        cachedText = text
        cachedHref = href
        onSpeakers(SpeechCast.speakers(spans))
        spokenText = text
        spokenHref = href
        lastSentence = -1
        followedPage = -1
        Log.i("KairnPerf", "tts tap→speak ${SystemClock.elapsedRealtime() - at}ms ${if (cached != null) "cached" else "extracted"}")
        // The plan is rebuilt from the live cast on every speak: a voice the reader just changed must be the
        // one that speaks, not the one that was saved when the book opened.
        // Cast off means one piece for the chapter, exactly as before the feature existed: no extra
        // utterance boundaries where nobody is changing voice.
        val plan = planFor(spans).let { p -> if (p.on) p.copy(spans = shifted(spans, from)) else CastPlan.NONE }
        engine.speak(text.substring(from), ui.prefs.speechRate, from, plan)
    }

    /** The voice's place in the chapter, at most every 300 ms. Paint the sentence, and turn the page. */
    private fun follow(at: Int) {
        if (!ui.speech.on || ui.speech.paused || spokenText.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - followAt < 300) return
        followAt = now
        val (s, e) = ReaderLogic.sentenceAt(spokenText, at)
        if (s != lastSentence) {
            lastSentence = s
            scope.launch { navigator()?.evaluateJavascript(ReaderFollow.highlight(s, e)) }
        }
        if (ui.prefs.scroll) return                     // a scroll view already shows the spoken place
        val href = spokenHref ?: ui.chapterHref ?: return
        val chapter = positionsByHref()[href] ?: return
        if (chapter.size < 2) return
        val progress = at.toFloat() / spokenText.length.coerceAtLeast(1)
        val idx = chapter.indexOfLast { (it.locations.progression ?: 0.0) <= progress }.coerceAtLeast(0)
        if (idx == followedPage) return
        followedPage = idx
        Log.i("KairnPerf", "tts follow page $idx of ${chapter.size} at ${(progress * 100).toInt()}%")
        scope.launch { navigator()?.go(chapter[idx], animated = false) }
    }

    /** The chapter ended by itself: turn the page and keep reading, unless this was the last one. */
    private fun advance() {
        // A timer set to "end of chapter" is fulfilled here, before the next chapter can start.
        if (endOfChapterTimer()) return
        val n = navigator() ?: run { ui.speech.on = false; return }
        val here = n.currentLocator.value?.href?.toString()
        val last = publication()?.readingOrder?.lastOrNull()?.href?.toString()
        if (here == null || here == last) { ui.speech.on = false; return }
        scope.launch { n.goForward(true) }
    }

    /**
     * Drives the exact path the TTS callback drives, from a test: takes the current chapter's text, pretends the
     * voice reached [ratio] of it, and lets [follow] turn the page. Used to prove the reader still turns pages
     * while the activity is stopped, i.e. behind a locked screen.
     */
    @androidx.annotation.VisibleForTesting
    internal fun followForTest(ratio: Float) = scope.launch {
        val p = publication() ?: return@launch
        val loc = navigator()?.currentLocator?.value ?: return@launch
        val text = withContext(Dispatchers.IO) { runCatching { p.content(loc)?.text() }.getOrNull() }
        if (text.isNullOrEmpty()) return@launch
        spokenText = text
        spokenHref = loc.href.toString()
        lastSentence = -1
        followedPage = -1
        followAt = 0L
        ui.speech.on = true
        ui.speech.paused = false
        ui.prefs = ui.prefs.copy(scroll = false)     // page mode: the case that has to navigate
        follow((text.length * ratio).coerceIn(0f, text.length - 1f).toInt())
    }
}
