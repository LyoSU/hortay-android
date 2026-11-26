package dev.lyo.hortay.data

import android.content.Context
import android.os.Build

/**
 * When the one-time "rate Hortay on Google Play" sheet may appear. Pure, so every gate and
 * boundary is unit-tested; the inputs come from [OnboardingStore] and [isInstalledFromPlay].
 *
 * The thresholds keep the ask away from people still deciding whether they like the app:
 * a week since this feature first saw the install, and use on several separate days, so a
 * single long session on day one never qualifies.
 */
object RatePromptPolicy {

    const val MIN_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    const val MIN_DISTINCT_DAYS: Int = 5

    const val PLAY_STORE_PACKAGE = "com.android.vending"

    fun isEligible(
        nowMs: Long,
        firstSeenMs: Long?,
        distinctDays: Int,
        shown: Boolean,
        installedFromPlay: Boolean,
    ): Boolean {
        if (shown || !installedFromPlay || firstSeenMs == null) return false
        // A clock set back before the first launch yields a negative age and simply waits.
        return nowMs - firstSeenMs >= MIN_AGE_MS && distinctDays >= MIN_DISTINCT_DAYS
    }

    /**
     * The distinct-day counter after a launch on [today] (local epoch day). A day already
     * counted is not counted again; any other day, including one before [lastCountedDay]
     * after a clock change, counts once and becomes the new reference.
     */
    fun nextDayCount(count: Int, lastCountedDay: Long?, today: Long): Int =
        if (lastCountedDay == today) count else count + 1

    /**
     * True when Google Play installed this package. GitHub APKs, the beta and debug builds
     * report another installer (or none), so they are never asked to rate a listing they
     * did not come from. Any failure means "not from Play".
     */
    fun isInstalledFromPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE_PACKAGE
    }.getOrDefault(false)
}
