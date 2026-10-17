plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

/**
 * Macrobenchmark module (androidx.benchmark). It is a com.android.test module, so it is ONLY built and
 * run by its own tasks (:benchmark:*), never by assembleDebug/assembleRelease - the app stays free of
 * test-only code. Every measurement uses the :app "benchmark" build type (non-debuggable, close to
 * release), which is what makes the numbers compare to a real user's phone.
 *
 * How to run (a device must be attached - none is, as of writing):
 *   gradle :benchmark:connectedBenchmarkReleaseAndroidTest            # all benchmarks
 *   gradle :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Startup
 *   then pull the report:  adb pull /sdcard/Android/media/app.kairn.benchmark/benchmarkData
 */
android {
    namespace = "app.kairn.benchmark"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        // The build type whose targetApp is the :app "benchmark" build. Name is what matters:
        // com.android.test creates a build type named "benchmark" that it points at the app's
        // "benchmark" build type via targetProjectPath.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    targetProjectPath = ":app"

    // Macrobenchmark must SELF-INSTRUMENT (the runner drives the test APK's own process, not a separate
    // one). Without this the runner aborts with NOT-SELF-INSTRUMENTING and no benchmark executes. This
    // is the AGP-DSL form; a gradle.properties flag is NOT read by AGP 8.5.2 for this property.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")

    // benchmark-macro:1.3.3 -> androidx.core:1.9.0 -> lifecycle-runtime:2.3.1. The test APK lives in the
    // SAME process as the app, so that old lifecycle-common (no Lifecycle$Event$Companion) shadowed the
    // app's 2.8.6 and the app died on launch with NoSuchFieldError before any test ran. Pin the test
    // module's lifecycle up to the app's version so one consistent set of classes is packaged.
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.6")
    implementation("androidx.lifecycle:lifecycle-common:2.8.6")
}
