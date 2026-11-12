package app.kairn.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Which engine voice and pitch a piece of text goes to. [spans] are absolute in the chapter text; [slots]
 * says which voice slot (1..[app.kairn.data.BookCast.SLOTS]) a speaker was given. [on] false means one voice.
 */
internal data class CastPlan(
    val on: Boolean = false,
    val spans: List<SpeechCast.Span> = emptyList(),
    val slots: Map<String, Int> = emptyMap(),
) {
    companion object { val NONE = CastPlan() }
}

/**
 * Reads a chapter aloud. One engine, the chapter queued as chunks so a long chapter still speaks, and a
 * callback when the last chunk finishes so the Activity can turn the page and carry on.
 *
 * With a [CastPlan] each utterance carries its own speaker, and the voices are applied BETWEEN utterances -
 * never inside one, because a voice can only be set while nothing is being spoken. The engine is asked for
 * two distinct voices in the system language and the rest of the cast comes from pitch on those two: a
 * phone's own TTS has far fewer voices than a book has characters.
 *
 * The engine binds asynchronously and a cold bind is seconds long, so [warm] is called when the reader opens
 * and a [speak] that arrives first is remembered and played when it is ready - a tap must never be a no-op.
 *
 * TextToSpeech has no pause, so a pause is a stop plus the position: onRangeStart reports the character
 * offset of every word, [onWord] carries that offset into the chapter text, and [resume] re-queues from it
 * instead of restarting the chapter. Nothing here plays in the background: the Activity stops it in onPause.
 */
internal class ReaderTts(
    context: Context,
    private val onStarted: () -> Unit,
    private val onEnded: () -> Unit,
    private val onFailed: () -> Unit,
    private val onWord: (Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val app = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = false
    private var waitingText: String? = null
    private var waitingFrom = 0
    private var waitingRate = 1f
    private var waitingPlan: CastPlan = CastPlan.NONE

    private var pieces: List<ReaderLogic.TtsPiece> = emptyList()
    private var base = 0               // where the spoken text starts in the chapter
    private var index = 0              // the piece being spoken
    private var offset = 0             // characters of that piece already heard
    private var firstIndex = 0         // where the last queueing started, and from which character
    private var firstOffset = 0
    private var playing = false
    private var paused = false
    private var rate = 1f
    private var initAt = 0L
    private var initMs = 0L
    private var speakAt = 0L
    private var started = false
    private var quiet = false          // our own stop()s must not be reported as failures

    private var plan: CastPlan = CastPlan.NONE
    private var slots: List<VoiceSlot> = emptyList()
    private var appliedSlot = -1
    private var lastDoneAt = 0L

    /** One way of sounding: a voice the engine has, and a pitch to tell it apart from its neighbours. */
    private data class VoiceSlot(val voice: Voice?, val pitch: Float)

    /** Bind the engine while the reader is already open, so the first tap does not pay for it. */
    fun warm() = ensureEngine()

    private fun ensureEngine() {
        if (engine != null) return
        initAt = SystemClock.elapsedRealtime()
        engine = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            initMs = SystemClock.elapsedRealtime() - initAt
            Log.i("KairnPerf", "tts init ${initMs}ms ok=$ready")
            if (!ready) { waitingText = null; main.post(onFailed); return@TextToSpeech }
            chooseVoice()
            waitingText?.let { text ->
                val from = waitingFrom; val r = waitingRate; val p = waitingPlan
                waitingText = null
                speak(text, r, from, p)
            }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val i = utteranceId?.substringBefore('/')?.toIntOrNull() ?: return
                index = i
                offset = if (i == firstIndex) firstOffset else 0
                // The number that decides whether a cast sounds broken: the silence between two utterances,
                // which is where a voice change lands. Measured on the device with KairnPerf: tts gap.
                if (lastDoneAt > 0) Log.i("KairnPerf", "tts gap ${SystemClock.elapsedRealtime() - lastDoneAt}ms before $i")
                if (started) return
                started = true
                Log.i("KairnPerf", "tts first audio ${SystemClock.elapsedRealtime() - speakAt}ms (init took ${initMs}ms)")
                main.post(onStarted)
            }

            // Word-level position: a pause resumes here, and the page and the highlight follow it.
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val i = utteranceId?.substringBefore('/')?.toIntOrNull() ?: return
                val piece = pieces.getOrNull(i) ?: return
                offset = (if (i == firstIndex) firstOffset else 0) + start
                val at = base + piece.start + offset
                main.post { onWord(at) }
            }

            override fun onDone(utteranceId: String?) {
                lastDoneAt = SystemClock.elapsedRealtime()
                if (utteranceId?.substringBefore('/') == (pieces.size - 1).toString()) {
                    playing = false
                    main.post(onEnded)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (quiet) return
                playing = false
                main.post(onFailed)
            }
        })
    }

    /**
     * Prefer a voice in the system language that needs no network; otherwise leave the engine's own choice
     * alone rather than forcing `language` and losing the voice data check. The same list becomes the cast:
     * two voices is what a phone realistically has, so slots 2 upwards are those voices at another pitch.
     */
    private fun chooseVoice() {
        val e = engine ?: return
        val at = SystemClock.elapsedRealtime()
        val sys = Locale.getDefault()
        val all = runCatching { e.voices }.getOrNull().orEmpty()
        val ranked = all.filter { it.locale.language == sys.language }
            .sortedByDescending { (if (it.locale == sys) 2 else 0) * 100 + if (it.isNetworkConnectionRequired) 0 else 50 }
        val best = ranked.firstOrNull()
        val second = ranked.firstOrNull { it.name != best?.name }
        if (best != null) runCatching { e.voice = best }
        slots = listOf(
            VoiceSlot(best, 1.0f),                          // 0: the narrator
            VoiceSlot(best, 0.80f),                        // 1: the narrator, deeper
            VoiceSlot(second ?: best, 1.0f),               // 2: the engine's other voice, when it has one
            VoiceSlot(second ?: best, 1.22f),              // 3: that voice, higher
            VoiceSlot(best, 1.34f),                        // 4: the last one left, and clearly not the narrator
        )
        Log.i(
            "KairnPerf",
            "tts voice ${best?.name ?: "engine default"} ${best?.locale ?: ""} picked in ${SystemClock.elapsedRealtime() - at}ms" +
                " (${all.size} voices, ${if (second != null) 2 else 1} usable)",
        )
    }

    /** [base] is where [text] sits in the chapter, so a spoken word maps back onto the page. */
    fun speak(text: String, speechRate: Float, base: Int = 0, plan: CastPlan = CastPlan.NONE) {
        ensureEngine()
        rate = speechRate
        this.base = base
        this.plan = plan
        speakAt = SystemClock.elapsedRealtime()
        started = false
        playing = false
        paused = false
        appliedSlot = -1
        lastDoneAt = 0
        if (!ready) { waitingText = text; waitingFrom = base; waitingRate = rate; waitingPlan = plan; return }
        pieces = ReaderLogic.ttsPieces(text, plan.spans)
        if (pieces.isEmpty()) { main.post(onFailed); return }
        playing = true
        queueFrom(0, 0)
    }

    /** Applies immediately: mid-chapter it re-queues from the word being spoken at the new rate. */
    fun setRate(speechRate: Float) {
        if (rate == speechRate) return
        rate = speechRate
        if (playing && !paused) queueFrom(index, offset)
    }

    /** A cast change is heard at once, from the word being spoken, not at the next chapter. */
    fun setCast(next: CastPlan) {
        plan = next
        if (playing && !paused) queueFrom(index, offset)
    }

    fun pause() {
        if (!playing || paused) return
        paused = true
        silentStop()
    }

    fun resume() {
        if (!playing || !paused) return
        paused = false
        queueFrom(index, offset)
    }

    fun stop() {
        playing = false
        paused = false
        waitingText = null
        silentStop()
    }

    fun shutdown() {
        silentStop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        waitingText = null
    }

    private fun silentStop() {
        quiet = true
        runCatching { engine?.stop() }
    }

    /** The slot a piece is spoken in: the narrator unless the cast is on and the piece has a speaker. */
    private fun slotFor(speaker: String): Int {
        if (!plan.on || speaker.isEmpty()) return 0
        return plan.slots[speaker] ?: 0
    }

    /**
     * Voice and pitch for one utterance. Both are applied only when the speaker changes, because a
     * `setVoice` between every sentence is a round trip to the engine on every sentence.
     */
    private fun applySlot(slot: Int) {
        val e = engine ?: return
        if (slot == appliedSlot) return
        appliedSlot = slot
        val s = slots.getOrNull(slot) ?: slots.firstOrNull() ?: return
        runCatching { s.voice?.let { e.voice = it } }
        runCatching { e.setPitch(s.pitch) }
    }

    private fun queueFrom(i0: Int, off: Int) {
        val e = engine ?: return
        quiet = false
        firstIndex = i0
        firstOffset = off
        e.setSpeechRate(rate)
        // The first utterance decides whether the cast is audible at all: it must carry the voice of the
        // piece it starts on, not the voice left over from the previous chapter.
        appliedSlot = -1
        var first = true
        for (i in i0 until pieces.size) {
            val piece = pieces[i]
            applySlot(slotFor(piece.speaker))
            val body = piece.text
            val text = if (i == i0 && off > 0) body.substring(off.coerceAtMost(body.length)) else body
            val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            e.speak(text, mode, null, "$i/${pieces.size}")
            first = false
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun slotForTest(speaker: String): Int = slotFor(speaker)

    @androidx.annotation.VisibleForTesting
    internal fun speakerCount(): Int = pieces.map { it.speaker }.distinct().size
}
