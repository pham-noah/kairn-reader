package app.kairn.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Reads a chapter aloud. One engine, the chapter queued as chunks so a long chapter still speaks, and a
 * callback when the last chunk finishes so the Activity can turn the page and carry on.
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
    private var waiting: String? = null

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

    /** Bind the engine while the reader is already open, so the first tap does not pay for it. */
    fun warm() = ensureEngine()

    private fun ensureEngine() {
        if (engine != null) return
        initAt = SystemClock.elapsedRealtime()
        engine = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            initMs = SystemClock.elapsedRealtime() - initAt
            Log.i("KairnPerf", "tts init ${initMs}ms ok=$ready")
            if (!ready) { waiting = null; main.post(onFailed); return@TextToSpeech }
            chooseVoice()
            waiting?.let { text -> waiting = null; speak(text, rate, base) }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val i = utteranceId?.substringBefore('/')?.toIntOrNull() ?: return
                index = i
                offset = if (i == firstIndex) firstOffset else 0
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
     * alone rather than forcing `language` and losing the voice data check.
     */
    private fun chooseVoice() {
        val e = engine ?: return
        val sys = Locale.getDefault()
        val best = runCatching {
            e.voices?.filter { it.locale.language == sys.language }
                ?.sortedByDescending { (if (it.locale == sys) 2 else 0) * 100 + if (it.isNetworkConnectionRequired) 0 else 50 }
                ?.firstOrNull()
        }.getOrNull()
        if (best != null) runCatching { e.voice = best }
        Log.i("KairnPerf", "tts voice ${best?.name ?: "engine default"} ${best?.locale ?: ""}")
    }

    /** [base] is where [text] sits in the chapter, so a spoken word maps back onto the page. */
    fun speak(text: String, speechRate: Float, base: Int = 0) {
        ensureEngine()
        rate = speechRate
        this.base = base
        speakAt = SystemClock.elapsedRealtime()
        started = false
        playing = false
        paused = false
        if (!ready) { waiting = text; return }
        pieces = ReaderLogic.ttsPieces(text)
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
        waiting = null
        silentStop()
    }

    fun shutdown() {
        silentStop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        waiting = null
    }

    private fun silentStop() {
        quiet = true
        runCatching { engine?.stop() }
    }

    private fun queueFrom(i0: Int, off: Int) {
        val e = engine ?: return
        quiet = false
        firstIndex = i0
        firstOffset = off
        e.setSpeechRate(rate)
        var first = true
        for (i in i0 until pieces.size) {
            val body = pieces[i].text
            val text = if (i == i0 && off > 0) body.substring(off.coerceAtMost(body.length)) else body
            val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            e.speak(text, mode, null, "$i/${pieces.size}")
            first = false
        }
    }
}
