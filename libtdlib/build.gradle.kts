plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.drinkless.tdlib"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // TdApi.java is post-processed by AddIntDef.php to sprinkle @IntDef/@LongDef
    // annotations from androidx.annotation. They're SOURCE-retention only, so
    // compileOnly is enough — no runtime jar shipped.
    compileOnly("androidx.annotation:annotation:1.10.0")
}
