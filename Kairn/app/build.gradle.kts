import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A release key outside the repo: with a key that never changes, an update installs over the old app and the
// library, places and settings survive. Without it (CI, F-Droid) the release is built unsigned, as before.
val keystoreProps = Properties().apply {
    val candidates = listOf(
        System.getenv("KAIRN_KEYSTORE_PROPS"),
        rootProject.file("../../Kairn-keys/keystore.properties").absolutePath,
    )
    candidates.filterNotNull().map(::File).firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "app.kairn"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    defaultConfig {
        applicationId = "app.kairn"
        minSdk = 26            // DEC-10: Android 8.0
        targetSdk = 36
        versionCode = 14
        versionName = "0.8.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
            isMinifyEnabled = true       // N-03: small install
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Only used by the :benchmark module (Macrobenchmark measures a NON-debuggable, close-to-release
        // build). initWith(debug) keeps the app's normal debug sources/DI; isDebuggable=false is what
        // makes the numbers mean anything - a debuggable build disables the JIT optimisations a real
        // user gets. Not assembled by assembleDebug/assembleRelease.
        create("benchmark") {
            initWith(buildTypes.getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // required by Readium
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeCompiler {
        // Emits build/compose_compiler/*-composables.txt & *-classes.txt, which state per-composable
        // whether it is restartable/skippable and WHY not. That is the evidence for "can this skip?",
        // instead of assuming @Immutable fixed it.
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
    // DEC-14 (F-Droid): no proprietary dependencies, no Play Services, no analytics (DEC-3).
    dependenciesInfo { includeInApk = false; includeInBundle = false }

    lint {
        // LogNotTimber only fires because a test dependency drags Timber onto the lint classpath. The app
        // has no Timber and plain Log is deliberate, so the check is meaningless here - disabling it keeps
        // the remaining warnings visible instead of burying them in 4 lines of noise.
        disable += "LogNotTimber"
        // The launcher icon must stay in mipmap-anydpi-V26 even though minSdk is 26: aapt2 refuses an
        // <adaptive-icon> that sits in a version-less folder ("resource mipmap/ic_launcher not found").
        // Lint's "merge this folder" advice is wrong for adaptive icons, so the check is off.
        disable += "ObsoleteSdkInt"
    }
}

// A release APK with no key is unsigned, and an unsigned APK on the releases page installs nothing. Only
// release assembly is guarded, so debug/CI test builds are never blocked; store builds pass -PallowUnsigned.
tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("bundle") }
    .matching { it.name.contains("Release") }
    .configureEach {
        doFirst {
            if (!hasReleaseKey && !project.hasProperty("allowUnsigned") && System.getenv("KAIRN_ALLOW_UNSIGNED") != "1") {
                throw GradleException(
                    "No release key: expected keystore.properties in ../../Kairn-keys/ or KAIRN_KEYSTORE_PROPS. " +
                        "A signed release is the only one that installs over an existing Kairn. " +
                        "Pass -PallowUnsigned (or KAIRN_ALLOW_UNSIGNED=1) for a store/CI build that signs itself."
                )
            }
        }
    }

dependencies {
    val readium = "3.0.0"
    implementation("org.readium.kotlin-toolkit:readium-shared:$readium")
    implementation("org.readium.kotlin-toolkit:readium-streamer:$readium")
    implementation("org.readium.kotlin-toolkit:readium-navigator:$readium")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")   // ActivityScenario for the reader tests
    androidTestImplementation(composeBom)                        // the BOM in `implementation` does NOT version these
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // org.json on the JVM, for data-layer tests

    // Ships a Baseline Profile: a list of the hot code paths on first launch, AOT-compiled at install,
    // so the Compose startup + first frame do not have to be JIT-warmed on the user's phone. The profile
    // itself is generated by :benchmark (BaselineProfileGenerator) into app/src/main/baseline-prof.txt.
    // 1.4.0+ is required for API 34+ (Android 15); 1.3.1 makes Macrobenchmark's installProfile throw.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
