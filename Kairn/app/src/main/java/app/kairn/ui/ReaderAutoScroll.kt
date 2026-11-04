package app.kairn.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/**
 * R-07: the page drifts down by itself and turns when it reaches the bottom.
 *
 * One JS interval moves the page inside the WebView - a per-frame Kotlin round-trip would be a wake-up and a hop
 * across the bridge every 16 ms - and the loop below only watches for the end of the chapter. A finger on the
 * page stops the drift so the reader can look at something, and it comes back 700 ms after the finger leaves so
 * a tap does not restart it under the next tap.
 *
 * It lives here rather than in the Activity because none of it is a navigator or a window concern: it is a
 * scroll loop with a hold, and the Activity now only decides when to start and stop it.
 */
internal class ReaderAutoScroll(
    private val navigator: () -> EpubNavigatorFragment?,
    private val scrollSpeed: () -> Float,
    private val density: () -> Float,
    /** Mirrors the live state onto the chrome, so the pill and the loop can never disagree. */
    private val scrolling: (Boolean) -> Unit,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var holdJob: Job? = null
    private var held = false                 // a finger is on the page: no drift, and no page turn

    val isRunning: Boolean get() = job != null

    fun start() {
        if (job != null) return
        scrolling(true)
        job = scope.launch {
            val n = navigator() ?: return@launch
            n.evaluateJavascript(script(step()))
            var last = -1f
            var still = 0
            while (isActive) {
                delay(1500)
                if (held) { last = -1f; still = 0; continue }   // held still by a finger, not by the bottom
                val y = n.evaluateJavascript("window.scrollY")?.toFloatOrNull() ?: -1f
                still = if (y == last) still + 1 else 0
                last = y
                if (still >= 2) { still = 0; n.goForward(true) }     // the bottom: turn and keep going
            }
        }
    }

    fun hold() {
        if (job == null || held) return
        held = true
        scope.launch { navigator()?.evaluateJavascript("clearInterval(window.__kairnAuto)") }
    }

    fun release() {
        if (job == null || !held) return
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(RESUME_MS)
            held = false
            navigator()?.evaluateJavascript(script(step()))
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        holdJob?.cancel()
        holdJob = null
        held = false
        scrolling(false)
        scope.launch { navigator()?.evaluateJavascript("clearInterval(window.__kairnAuto)") }
    }

    private fun step() = (scrollSpeed() * density() / 50f).coerceAtLeast(0.5f)

    private fun script(step: Float) = "window.__kairnAuto && clearInterval(window.__kairnAuto);" +
        "window.__kairnAuto = setInterval(function(){ window.scrollBy(0, $step) }, 20)"

    private companion object {
        /** Long enough that the next tap is not swallowed, short enough to feel like it never stopped. */
        const val RESUME_MS = 700L
    }
}
