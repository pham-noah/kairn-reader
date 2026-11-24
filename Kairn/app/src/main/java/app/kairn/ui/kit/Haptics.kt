package app.kairn.ui.kit

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
    fun tick() = fire(HapticFeedbackConstants.KEYBOARD_TAP)
    fun toggle() = fire(HapticFeedbackConstants.CLOCK_TICK)
    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS)

    private fun fire(effect: Int) {
        if (enabled) view?.performHapticFeedback(effect)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val enabled = LocalHaptics.current
    val view = LocalView.current
    return remember(enabled, view) { Haptics(if (view.isInEditMode) null else view, enabled) }
}
