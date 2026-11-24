package app.kairn.ui.kit

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.kairn.ui.theme.LocalHaptics

/**
 * Three strengths, because a switch and a scroll-drag are not the same event:
 *
 * - [tick] for a control being pressed - 12ms at 47% amplitude, the shortest thing a thumb reads as a click.
 * - [toggle] for a switch landing in its new position, once the value has actually changed - 20ms at 74%.
 * - [longPress] for a press that has become a gesture (picking a book, starting a reorder) - 32ms at full, so
 *   the finger learns the mode changed without the eye having to.
 *
 * This used to go through `View.performHapticFeedback` with `VIRTUAL_KEY` and `CONFIRM`. That path is a request,
 * not a vibration: the system applies its own touch-feedback setting, its own intensity, and vendor builds ignore
 * `FLAG_IGNORE_GLOBAL_SETTING`, so on a phone with touch feedback turned down the app felt like it had no haptics
 * at all - the reported defect. A `Vibrator` and an explicit `VibrationEffect` is the same buzz the app asked for
 * at the amplitude it asked for, and it is the only path a test can prove, because `ShadowVibrator` records what
 * reached the hardware while `performHapticFeedback` only returns a boolean nobody can check.
 *
 * `USAGE_TOUCH` on the attributes keeps it honest in the battery and DND logs: this is a touch response, not an
 * alarm. Nothing here fires when the setting is off, and nothing fires from a passive draw: haptics on scroll
 * would be a buzz in the pocket for a page that merely moved under the finger.
 */
class Haptics(private val vibrator: Vibrator?, private val enabled: Boolean) {
    private val amplitude = vibrator?.hasAmplitudeControl() == true

    fun tick() = fire(12L, 120)
    fun toggle() = fire(20L, 190)
    fun longPress() = fire(32L, 255)

    private fun fire(ms: Long, strength: Int) {
        val v = vibrator ?: return
        if (!enabled || !v.hasVibrator()) return
        val effect = VibrationEffect.createOneShot(
            ms,
            if (amplitude) strength else VibrationEffect.DEFAULT_AMPLITUDE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            v.vibrate(effect)
        }
    }
}

/** The system vibrator, or null on a device that has none (and in previews). */
fun vibratorOf(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

@Composable
fun rememberHaptics(): Haptics {
    val enabled = LocalHaptics.current
    val context = LocalContext.current
    return remember(enabled, context) { Haptics(vibratorOf(context), enabled) }
}
