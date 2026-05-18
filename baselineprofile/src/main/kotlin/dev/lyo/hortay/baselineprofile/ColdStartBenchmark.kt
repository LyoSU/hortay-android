package dev.lyo.hortay.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start macrobenchmark, split into two scenarios so the snapshot store's
 * value can be measured empirically rather than argued about:
 *
 * - [coldStartWithSnapshot] — leaves the persisted snapshot intact between
 *   iterations. Represents the steady-state user: TDLib's local cache is
 *   warm, [dev.lyo.hortay.data.TimelineSnapshotStore] paints `(chatId, messageId)`
 *   pairs from a previous session before the first refresh completes.
 *
 * - [coldStartWithoutSnapshot] — wipes `timeline_snapshot` between iterations.
 *   Represents the first-ever launch path (and is the comparison point that
 *   tells us whether keeping the snapshot store is still pulling its weight).
 *
 * Both scenarios use [StartupMode.COLD] + [StartupTimingMetric], which captures
 * `timeToInitialDisplayMs` (first frame produced by `MainActivity`) and, when
 * the activity calls `reportFullyDrawn()`, `timeToFullDisplayMs`. The diff
 * between the two scenarios on `timeToInitialDisplayMs` is exactly the
 * "snapshot paint" win — if it's < 50 ms on a representative device, the store
 * is overhead-only and can be removed.
 *
 * **Why a separate test class from [BaselineProfileGenerator]:**
 * - `BaselineProfileRule.collect` only emits a baseline profile artefact;
 *   it doesn't run [StartupTimingMetric] and can't be filtered by category.
 * - Keeping this in its own class lets the benchmark be invoked independently
 *   without re-running profile generation (which is slower and needs a
 *   rooted/userdebug device on some configurations).
 *
 * **Run it later (does not block baseline profile generation):**
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
 *     -P android.testInstrumentationRunnerArguments.class=\
 *        dev.lyo.hortay.baselineprofile.ColdStartBenchmark
 * ```
 *
 * Or to target a single scenario:
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
 *     -P android.testInstrumentationRunnerArguments.class=\
 *        dev.lyo.hortay.baselineprofile.ColdStartBenchmark#coldStartWithoutSnapshot
 * ```
 *
 * Results land under `baselineprofile/build/outputs/connected_android_test_additional_output/`.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Cold start with [dev.lyo.hortay.data.TimelineSnapshotStore] intact.
     *
     * The first iteration primes the store (real cold start with no snapshot);
     * subsequent iterations measure the steady-state cold-start experience —
     * the one a returning user sees.
     */
    @Test
    fun coldStartWithSnapshot() {
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
    }

    /**
     * Cold start with the snapshot file deleted before each iteration.
     *
     * Represents the worst case: TDLib's own DB is still warm (so the second
     * paint after refresh is fast), but the snapshot store can't contribute its
     * first-paint shortcut. Compare `timeToInitialDisplayMs` against
     * [coldStartWithSnapshot] to read the store's marginal value directly.
     */
    @Test
    fun coldStartWithoutSnapshot() {
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            setupBlock = {
                pressHome()
                clearStartupSnapshot()
            },
        ) {
            startActivityAndWait()
        }
    }

    /**
     * Deletes the DataStore-preferences file backing [TimelineSnapshotStore]
     * for the target package.
     *
     * The file name `timeline_snapshot` matches the `preferencesDataStore(name = ...)`
     * declaration in `data/TimelineSnapshotStore.kt`. DataStore writes it as
     * `<files>/datastore/<name>.preferences_pb`. Removing it forces the next
     * cold start to take the "no prior session" path.
     *
     * We use `run-as` so this works on user-debug devices without needing
     * `adb root`; the package is debuggable=false in the `benchmark` build
     * type, but the macrobenchmark instrumentation runs with sufficient
     * privilege via `am instrument` to delete files in the target's
     * `/data/data/<pkg>/files/` tree on profileable builds.
     */
    private fun MacrobenchmarkScope.clearStartupSnapshot() {
        val path = "/data/data/$PACKAGE/files/datastore/timeline_snapshot.preferences_pb"
        device.executeShellCommand("run-as $PACKAGE rm -f $path")
    }

    private companion object {
        const val PACKAGE = "dev.lyo.hortay"

        /**
         * Five iterations is the Macrobenchmark default for `StartupTimingMetric`.
         * Enough for a stable median without blowing the per-PR test budget
         * (~30 s per scenario on a Pixel-class device).
         */
        const val ITERATIONS = 5
    }
}
