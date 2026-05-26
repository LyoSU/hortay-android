plugins {
    // AGP 9 enables Kotlin compilation built-in (no separate kotlin-android plugin —
    // same as :app and :libtdlib). WebmAlphaNative.kt compiles under this alone.
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.lyo.hortay.webm"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
