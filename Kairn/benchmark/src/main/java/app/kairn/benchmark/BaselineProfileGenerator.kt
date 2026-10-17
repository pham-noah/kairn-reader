package app.kairn.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/main/baseline-prof.txt by driving the app's startup and first library frame.
 * Run once per meaningful UI change (or in CI) - the profile is committed and shipped.
 *
 *   gradle :benchmark:connectedBenchmarkReleaseAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=app.kairn.benchmark.BaselineProfileGenerator
 *
 * Writes to app/src/main/baseline-prof.txt; rebuild :app afterwards so profileinstaller packages it.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = "app.kairn",
        profileBlock = {
            startActivityAndWait()
            // Wait for the Library title, i.e. the first real frame - not just the splash.
            device.wait(Until.hasObject(By.text("Library")), 5_000)
        },
    )
}
