package app.kairn.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads a chapter aloud. One engine, the chapter queued as chunks so a long chapter still speaks, and a
 * callback when the last chunk finishes so the Activity can turn the page and carry on.
 *
 * The engine initialises asynchronously, so a [speak] that arrives first is remembered and played when it is
 * ready - tapping the button on a cold reader must not be a no-op. Nothing here plays in the background: the
 * Activity stops it in onStop.
 */
internal class ReaderTts(
    context: Context,
    private val onState: (Boolean) -> Unit,
    private val onChapterEnd: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val app = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = false
    private var waiting: String? = null
    private var queued = 0

    private fun ensureEngine() {
        if (engine != null) return
        engine = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { waiting = null; main.post { onState(false) }; return@TextToSpeech }
            runCatching { engine?.language = Locale.getDefault() }
            waiting?.let { text -> waiting = null; speak(text) }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.substringBefore('/') == (queued - 1).toString()) {
                    main.post { onState(false); onChapterEnd() }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onState(false) }
            }
        })
    }

    fun speak(text: String) {
        ensureEngine()
        if (!ready) { waiting = text; return }
        val e = engine ?: return
        e.stop()
        val chunks = ReaderLogic.ttsChunks(text)
        if (chunks.isEmpty()) { onState(false); return }
        queued = chunks.size
        chunks.forEachIndexed { i, chunk ->
            e.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$i/${chunks.size}")
        }
        onState(true)
    }

    fun stop() {
        waiting = null
        runCatching { engine?.stop() }
        onState(false)
    }

    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        waiting = null
    }
}
