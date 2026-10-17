package app.kairn.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real frame timings + startup times on a device, measured on the non-debuggable :app "benchmark" build.
 * This is the runtime evidence the Compose stability report can only predict.
 *
 *   gradle :benchmark:connectedBenchmarkReleaseAndroidTest
 *   gradle :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Startup
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() = rule.measureRepeated(
        packageName = "app.kairn",
        metrics = listOf(StartupTimingMetric()),
        // CompilationMode.None = a truly COLD start (nothing AOT-compiled), the closest thing to what a
        // user feels on the very first launch. Compare with CompilationMode.Partial to see the baseline
        // profile's effect once one is generated.
        compilationMode = CompilationMode.None(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Library")), 5_000)
    }

    @Test
    fun startupCompilationPartial() = rule.measureRepeated(
        packageName = "app.kairn",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Library")), 5_000)
    }

    /** Frame timings while scrolling the library grid - the metric that shows cover-decode jank. */
    @Test
    fun scrollLibraryFrames() = rule.measureRepeated(
        packageName = "app.kairn",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            startActivityAndWait()
            device.wait(Until.hasObject(By.text("Library")), 5_000)
        },
    ) {
        val grid = device.findObject(By.clazz("androidx.compose.ui.platform.ComposeView"))
        repeat(3) {
            grid?.fling(androidx.test.uiautomator.Direction.DOWN)
            device.waitForIdle()
            grid?.fling(androidx.test.uiautomator.Direction.UP)
            device.waitForIdle()
        }
    }
}
