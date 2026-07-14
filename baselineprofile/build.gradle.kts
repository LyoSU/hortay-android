plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.lyo.hortay.baselineprofile"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    sourceSets {
        named("main") { java.srcDirs("src/main/kotlin") }
    }
}

// Tell baselineProfile which target variant of :app to run the macrobenchmark
// against. We use the `benchmark` build type defined in app/build.gradle.kts —
// it's `release`-equivalent (minified, R8) but signed with debug keys + profileable,
// which is exactly what the benchmark probe needs to read AOT-relevant code paths.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
