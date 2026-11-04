package app.kairn.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.kairn.R
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
 * It was 150 lines of policy inside the Activity, mixed with navigation, marks and the window. The Activity
 * still owns the two things that are its own: the sleep timer (it schedules a toast and a delay) and the
 * foreground service that keeps the process alive while the screen is off.
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
        val text = withContext(Dispatchers.IO) { runCatching { p.content(loc)?.text() }.getOrNull() }
        if (text.isNullOrBlank()) {
            ui.speech.on = false; ui.speech.starting = false
            toast(R.string.s_tts_chapter_failed)
            return@launch
        }
        val from = ReaderLogic.ttsStartOffset(text, loc.locations.progression?.toFloat() ?: 0f)
        spokenText = text
        spokenHref = loc.href.toString()
        lastSentence = -1
        followedPage = -1
        engine.speak(text.substring(from), ui.prefs.speechRate, from)
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
