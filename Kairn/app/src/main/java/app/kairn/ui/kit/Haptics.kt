package app.kairn.ui.kit

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import app.kairn.ui.theme.LocalHaptics

/**
 * Three strengths, because a switch and a scroll-drag are not the same event:
 *
 * - [tick] for a control being pressed - the shortest, quietest one Android has.
 * - [toggle] for a switch landing in its new position, once the value has actually changed.
 * - [longPress] for a press that has become a gesture (picking a book, starting a reorder), so the finger learns
 *   the mode changed without the eye having to.
 *
 * Nothing here fires when the setting is off, and nothing fires from a passive draw: haptics on scroll would be
 * a buzz in the pocket for a page that merely moved under the finger.
 */
class Haptics(private val view: android.view.View?, private val enabled: Boolean) {
    // KEYBOARD_TAP and CLOCK_TICK are the two quietest constants Android has, and on most phones they are a
    // whisper the thumb cannot feel - which reads as "the app has no haptics at all". VIRTUAL_KEY (27+) and
    // CONFIRM (30+) are the ones the system itself uses for a key press and a landed action.
    fun tick() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) HapticFeedbackConstants.VIRTUAL_KEY
        else HapticFeedbackConstants.KEYBOARD_TAP
    )

    fun toggle() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.CLOCK_TICK
    )

    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS)

    /**
     * FLAG_IGNORE_GLOBAL_SETTING on purpose: this app carries its own switch (Settings > Haptics), so the switch
     * the user can see is the switch that decides. Respecting the system flag as well made the setting a no-op on
     * any phone with touch feedback turned down.
     */
    private fun fire(effect: Int) {
        if (enabled) view?.performHapticFeedback(effect, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val enabled = LocalHaptics.current
    val view = LocalView.current
    return remember(enabled, view) { Haptics(if (view.isInEditMode) null else view, enabled) }
}
