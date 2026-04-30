package dev.lyo.hortay.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the AOT baseline profile shipped with release builds.
 *
 * The macrobenchmark runtime launches the app fresh, lets us drive a representative
 * scenario, then captures the JIT-hottest classes/methods and writes them as a
 * baseline profile. AGP picks the result up via the `baselineProfile {}` configuration
 * in `app/build.gradle.kts` and bundles it into the APK; `androidx.profileinstaller`
 * then applies it to ART's compilation database so the same code paths run AOT-compiled
 * from the very first cold launch on a user's device.
 *
 * Scenario coverage: cold start to "first useful frame". This already covers
 * `Application.onCreate`, `AppGraph` wiring, TDLib JNI startup, Compose first
 * composition, and the very first render of either the auth screen (logged out)
 * or the timeline (logged in) — that's where the bulk of cold-start cost lives.
 *
 * **Run this manually** when you want to refresh the profile:
 *
 * ```
 * ./gradlew :baselineprofile:pixel6Api33BenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
 * ```
 *
 * or simply `./gradlew :app:generateBaselineProfile` which orchestrates this for
 * the connected device. The resulting profile lands in
 * `app/src/release/generated/baselineProfiles/baseline-prof.txt` and should be
 * checked into version control.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = PACKAGE,
            includeInStartupProfile = true,
        ) {
            // Cold start. `pressHome()` first ensures we're at a known starting
            // surface (no leftover task), then `startActivityAndWait` launches our
            // MainActivity and blocks until it reports its first rendered frame.
            // That window is exactly what we want AOT-compiled.
            pressHome()
            startActivityAndWait()
        }
    }

    private companion object {
        const val PACKAGE = "dev.lyo.hortay"
    }
}
