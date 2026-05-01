import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

val telegramProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val telegramApiId: String = telegramProps.getProperty("telegram.apiId") ?: "0"
val telegramApiHash: String = telegramProps.getProperty("telegram.apiHash") ?: ""

// Optional release signing. Drop a keystore.properties (gitignored) at the
// project root with: storeFile=, storePassword=, keyAlias=, keyPassword=.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Git metadata for beta builds. Lazy so we only fork `git` if a beta variant is
// actually being assembled, and we never crash a fresh checkout that lacks .git.
val gitShortSha: String by lazy {
    runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
    }.getOrDefault("unknown")
}
val gitCommitCount: Int by lazy {
    runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)
}

android {
    namespace = "dev.lyo.hortay"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lyo.hortay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Real Android devices are arm64-v8a only. Skipping x86_64 in release
            // halves the APK by dropping the redundant ~24 MB libtdjni.so copy.
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
        debug {
            isDebuggable = true
            // Keep x86_64 for emulator workflows in debug builds.
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        // Beta channel for tester sideload distribution. Inherits release (R8 +
        // resource shrink + arm64-only + release signing key) so testers exercise
        // production-shaped code, but lives at dev.lyo.hortay.beta so it installs
        // alongside any prod build. versionCode + SHA in versionName are wired up
        // in the androidComponents block below so each commit produces a unique
        // installable artifact (testers get in-place updates, no uninstall dance).
        create("beta") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
        }
        // Build type for Macrobenchmark / Baseline Profile generation. Same as release
        // (minified, R8-optimized) so the profile maps to real production code, but
        // signed with debug keys and marked profileable so the benchmark tooling can
        // attach. We don't ship this — it's only consumed by the :baselineprofile module.
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }

    // Bundle the generated baseline profile into release builds. The :baselineprofile
    // module produces it from a Macrobenchmark cold-start scenario; AGP picks the
    // result up here and ART's profileinstaller bakes it into the install image so
    // the hot startup path runs as AOT-compiled code from the very first launch.
    baselineProfile {
        mergeIntoMain = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val isBeta = variant.buildType == "beta"
        variant.outputs.forEach { output ->
            if (isBeta) {
                // Per-commit versionCode keeps Android happy with in-place updates
                // (otherwise testers would need to uninstall to switch betas).
                output.versionCode.set(gitCommitCount)
                val base = output.versionName.orNull ?: "0.0.0"
                output.versionName.set("$base-$gitShortSha")
            }
            val versionName = output.versionName.orNull ?: "unversioned"
            output.outputFileName.set("hortay-$versionName-${variant.name}.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.text.google.fonts)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.lottie.compose)

    implementation(project(":libtdlib"))

    // Runtime installer for the AOT baseline profile bundled by AGP's
    // baselineProfile {} block above. Without it, the profile sits in the APK but
    // is never applied to ART's compilation database.
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
