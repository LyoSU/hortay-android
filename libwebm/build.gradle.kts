plugins {
    // AGP 9 enables Kotlin compilation built-in (no separate kotlin-android plugin —
    // same as :app and :libtdlib). WebmAlphaNative.kt compiles under this alone.
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.lyo.hortay.webm"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26

        ndk {
            // arm64-v8a only for now: libvpx's armv7 NEON asm path doesn't cross-compile cleanly
            // under NDK r27 clang (see scripts/build-ffmpeg.sh TODO). 32-bit devices simply lack
            // libhortaywebm.so and WebmAlphaDecoder falls back to the static thumbnail.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake { cFlags += "-std=c11" }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
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
