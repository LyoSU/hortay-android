import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.sqldelight)
}

val telegramProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val telegramApiId: String = telegramProps.getProperty("telegram.apiId") ?: "0"
val telegramApiHash: String = telegramProps.getProperty("telegram.apiHash") ?: ""

// Release signing. Drop a keystore.properties (gitignored) at the project root
// with: storeFile=, storePassword=, keyAlias=, keyPassword=. Debug builds work
// without it; release/beta packaging fails fast at task-graph time (see
// gradle.taskGraph.whenReady block below).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Fail before any work happens if a release/beta packaging task is queued
// without signing config. AGP would otherwise produce an unsigned APK that
// silently misses upgrade installs / Play Console rejection — far worse than a
// build-time error here. lintRelease, assembleRelease without packaging, and
// debug tasks all stay unaffected.
gradle.taskGraph.whenReady {
    val needsSigning = allTasks.any { task ->
        task.name.matches(Regex("^(package|bundle)(Release|Beta).*"))
    }
    if (needsSigning && !keystoreProps.containsKey("storeFile")) {
        throw GradleException(
            "Release/Beta packaging requires keystore.properties at the project root.\n" +
                "Expected keys: storeFile, storePassword, keyAlias, keyPassword.\n" +
                "See app/build.gradle.kts:17 and CLAUDE.md (Setup-delta) for details."
        )
    }
    val packagingTask = allTasks.firstOrNull { task ->
        task.name.matches(Regex("^(package|bundle)(Release|Beta).*"))
    }
    if (packagingTask != null) {
        val variant = if (packagingTask.name.contains("Beta")) "beta" else "release"
        val childSafety = (project.findProperty("HORTAY_CHILD_SAFETY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        val privacy = (project.findProperty("HORTAY_PRIVACY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        if (childSafety == null) {
            error("HORTAY_CHILD_SAFETY_POLICY_URL must be set in gradle.properties for $variant builds")
        }
        if (privacy == null) {
            error("HORTAY_PRIVACY_POLICY_URL must be set in gradle.properties for $variant builds")
        }
    }
}

// Git metadata for release + beta builds. Lazy so we only fork `git` when a
// signing variant is actually being assembled, and we never crash a fresh
// checkout that lacks .git.
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
    // compileSdk = 37 is forced by Compose UI 1.12.0-alpha02 (transitive dep of
    // material3 1.5.0-alpha19, which we pin explicitly for M3 Expressive). targetSdk
    // stays at 36 — runtime behaviour we tested + Play certified — bumping just the
    // compile-time API surface is the canonical Google-recommended way to opt into a
    // new compose track without flipping app behaviour at runtime.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lyo.hortay"
        minSdk = 26
        targetSdk = 36
        // Sentinel only. The real versionCode for release + beta is wired in
        // androidComponents.onVariants below, derived from `git rev-list --count`
        // so we never trip Play Console's "this versionCode is already used"
        // error from a forgotten manual bump. Debug installs keep this 1 — they
        // never go through Play.
        versionCode = 1
        versionName = "0.4.0"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        // CSAE-compliance URLs. Values come from gradle.properties so they can be
        // updated without touching the build script. Both are string constants baked
        // into the APK/AAB at compile time; no runtime network fetch.
        val childSafetyProp = (project.findProperty("HORTAY_CHILD_SAFETY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        val privacyProp = (project.findProperty("HORTAY_PRIVACY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        buildConfigField("String", "CHILD_SAFETY_POLICY_URL", "\"${childSafetyProp ?: "https://dev.lyo.hortay/child-safety"}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${privacyProp ?: "https://dev.lyo.hortay/privacy"}\"")

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
                // Bundle full debug symbols (function names + line numbers) for
                // libtdjni.so into the .aab so Play Console's crash reporter can
                // symbolicate native stack frames. Stripped from the user-facing
                // APK by Play during dynamic delivery — adds ~tens of MB to the
                // bundle on the developer side, zero impact on download size.
                debugSymbolLevel = "FULL"
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

// Anonymous web-mode database. Schema files live under
// app/src/main/sqldelight/dev/lyo/hortay/data/web/db/*.sq; SQLDelight generates
// typed DAO sources at build time. Migrations sit alongside as `<n>.sqm`.
//
// Why a separate database (not bundled into TDLib's storage): TDLib persists its
// own MTProto state under `cache/td/` — opaque to us, owned by the daemon.
// Web-mode reads public preview HTML and has zero overlap with TDLib's data
// model. Sharing storage would also fight TDLib's vacuum/optimize cadence.
sqldelight {
    databases {
        create("WebDatabase") {
            packageName.set("dev.lyo.hortay.data.web.db")
            // Verify each migration round-trips schema → migration → schema. Catches
            // accidental column type changes that SQLite would silently accept.
            verifyMigrations.set(true)
            // Generate suspend functions on the queries that must run inside a
            // transaction — keeps callers from accidentally hopping threads
            // mid-write.
            generateAsync.set(false)
            // Persisted baseline schema snapshots (one .db file per version) live
            // under the source set so a diff in a PR shows the schema change next
            // to the migration that produced it. The `generateWebDatabaseSchema`
            // task writes the next snapshot; `verifyWebDatabaseMigration` replays
            // each .sqm against the previous snapshot and asserts the resulting
            // schema matches. Without this directory configured, the verify task
            // fails with "Verifying a migration requires a database file" and
            // the migration pipeline is plumbed but never actually exercised.
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
        }
    }
}

androidComponents {
    onVariants { variant ->
        val isBeta = variant.buildType == "beta"
        val isRelease = variant.buildType == "release"
        variant.outputs.forEach { output ->
            if (isBeta || isRelease) {
                // Per-commit versionCode for both signed channels:
                //   • Beta — keeps Android happy with in-place updates between
                //     consecutive testing builds (otherwise testers would need to
                //     uninstall to switch betas).
                //   • Release — sidesteps Play Console's "versionCode already in
                //     use" rejection. Each release commit produces a unique,
                //     monotonically-increasing code without manual bumps in
                //     defaultConfig. Trade-off: you must commit before
                //     `bundleRelease`. A no-op rebuild on the same SHA would
                //     otherwise produce a code Play already saw.
                // Beta and release live at separate package suffixes
                // (.beta vs no suffix), so they don't compete for the same
                // Play track even though they share the same number.
                output.versionCode.set(gitCommitCount)
            }
            if (isBeta) {
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
    implementation(libs.compose.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.text.google.fonts)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)

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

    // Anonymous web-mode pipeline: HTTP fetch + HTML parse for t.me/s/<channel>.
    // OkHttp is already pulled transitively by coil-network-okhttp, but we declare
    // it explicitly so the web client doesn't depend on Coil's internal version
    // pinning. Jsoup parses Telegram's public channel preview pages — the only
    // way to read public channels without an authenticated TDLib session.
    implementation(libs.okhttp)
    implementation(libs.jsoup)

    // Custom Tabs for external links in the Safety section (child safety + privacy
    // policy). Uses the system browser in a modal overlay, stays within the app
    // visually without forking into a separate WebView activity.
    implementation("androidx.browser:browser:1.8.0")

    // SQLDelight: typed DAO + Flow integration for the web.db database. Android
    // driver is the runtime; coroutines-extensions adds the asFlow() bridge so a
    // SELECT returns a Flow that re-emits whenever any of its source tables change
    // (queries are reference-counted internally, so observers cost nothing while
    // unsubscribed). Primitive-adapters covers Long↔Boolean / Long↔Instant style
    // mappings without hand-rolling each ColumnAdapter.
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines.extensions)
    implementation(libs.sqldelight.primitive.adapters)

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
