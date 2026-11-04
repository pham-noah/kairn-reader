package app.kairn.ui

import android.view.Window
import android.view.WindowManager
import app.kairn.data.ReaderPrefs
import app.kairn.data.ThemeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences

/**
 * R-03/R-05/R-06/R-10: everything a [ReaderPrefs] turns into - the engine's [EpubPreferences], the page's
 * brightness, the theme the chrome is drawn with - in one place, so the Activity carries the wiring and not
 * the policy.
 *
 * The mapping itself stays in `ReaderPrefsMapping` (pure, tested without an Activity). This class only adds
 * the two things that need a window, a navigator and a scope: when to apply, and what to write down.
 *
 * A slider emits on every drag tick, so submitting preferences to Readium and writing the book's JSON at that
 * rate makes the Display sheet heavy. The UI state follows immediately ([schedule] does not touch it); the
 * engine and the disk get the settled value [SETTLE_MS] later. `onPause` flushes, so a quick close cannot
 * lose the last change.
 */
internal class ReaderPrefsApplier(
    private val window: Window,
    /** Read through a lambda: the navigator is created after this class, and replaced on rotation. */
    private val navigator: () -> EpubNavigatorFragment?,
    private val autoSwitch: () -> Boolean,
    private val night: () -> Boolean,
    private val isTablet: () -> Boolean,
    private val persist: (ReaderPrefs) -> Unit,
    /** Auto scroll only exists in scroll mode, so leaving that mode stops it before anything is submitted. */
    private val onLeavingScrollMode: () -> Unit,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** The theme the chrome is drawn with, with follow-system and the current night resolved. */
    fun theme(p: ReaderPrefs): ThemeId = effectiveTheme(p, autoSwitch(), night())

    /** What the engine renders with. */
    fun epub(p: ReaderPrefs): EpubPreferences = toEpubPreferences(p, autoSwitch(), night(), isTablet())

    /** The debounced path: a drag in progress settles, then one save and one apply. */
    fun schedule(p: ReaderPrefs) {
        job?.cancel()
        job = scope.launch {
            delay(SETTLE_MS)
            persist(p)                 // R-10: per book
            apply(p)
        }
    }

    /** Apply now - a configuration change, a reset, or the flush on close. */
    fun apply(p: ReaderPrefs) {
        if (!p.scroll) onLeavingScrollMode()
        navigator()?.submitPreferences(epub(p))
        window.attributes = window.attributes.also {
            it.screenBrightness =
                if (p.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else p.brightness
        }
    }

    private companion object {
        /** Long enough that a drag stops emitting, short enough that a tap feels immediate. */
        const val SETTLE_MS = 120L
    }
}
