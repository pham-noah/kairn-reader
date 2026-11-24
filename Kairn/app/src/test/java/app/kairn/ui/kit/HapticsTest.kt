package app.kairn.ui.kit

import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The haptics fix, measured rather than asserted.
 *
 * The old implementation called `View.performHapticFeedback`, which returns a boolean and lets the system decide
 * whether anything happens - so no test could tell a working buzz from a request the phone silently dropped, and
 * on Noah's phone every one of them was dropped. These read `ShadowVibrator`, which records the effect that
 * reached the vibrator: a duration in milliseconds per strength, and silence when the setting is off or the
 * device has no vibrator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticsTest {

    private lateinit var vibrator: Vibrator

    @Before
    fun setUp() {
        vibrator = vibratorOf(ApplicationProvider.getApplicationContext())!!
        shadowOf(vibrator).setHasVibrator(true)
        shadowOf(vibrator).setHasAmplitudeControl(true)
    }

    private fun ms(shot: () -> Unit): Long {
        shot()
        return shadowOf(vibrator).milliseconds
    }

    @Test
    fun enabled_tick_reaches_the_vibrator() {
        Haptics(vibrator, true).tick()
        assertTrue("a tick must actually vibrate", shadowOf(vibrator).isVibrating)
        assertEquals(12L, shadowOf(vibrator).milliseconds)
    }

    @Test
    fun the_three_strengths_are_ordered() {
        val tick = ms { Haptics(vibrator, true).tick() }
        val toggle = ms { Haptics(vibrator, true).toggle() }
        val long = ms { Haptics(vibrator, true).longPress() }
        assertTrue("toggle must outlast tick: $toggle vs $tick", toggle > tick)
        assertTrue("long press must outlast toggle: $long vs $toggle", long > toggle)
    }

    @Test
    fun off_is_silent() {
        Haptics(vibrator, false).toggle()
        assertFalse("the setting being off must mean no vibration", shadowOf(vibrator).isVibrating)
    }

    @Test
    fun a_device_without_a_vibrator_is_silent_and_does_not_crash() {
        shadowOf(vibrator).setHasVibrator(false)
        Haptics(vibrator, true).longPress()
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun no_vibrator_at_all_does_not_crash() {
        Haptics(null, true).tick()
    }
}
