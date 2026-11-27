package dev.lyo.hortay.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * One-shot "has the user already been shown this?" flags for feature-discovery UI.
 *
 * These are device-level UX flags, not account data: a second account signing in on the
 * same install has already learned the gestures, so nothing here is wiped on logout and no
 * `AppGraph.session.onEnd` hook is registered. All flags default to false and only ever
 * flip to true.
 *
 * Upgrade seeding: the feed-order question shipped after 0.12.0, and its flag defaulting to
 * false would ask every upgrading user — including those who picked an order in Settings
 * long ago. The first time [feedOrderChosen] is read on an install that has never been
 * seeded, the store decides once whether this is an EXISTING install and, if so, records
 * the question as answered. Signal: PackageManager `lastUpdateTime > firstInstallTime`,
 * i.e. the APK was updated in place at least once (a fresh install has them equal). The
 * settings DataStore can't be peeked for a persisted `feed_order` — opening a second
 * DataStore on its file in-process is illegal and its keys are private. Edge cases: a fresh
 * install of this version that receives an update before ever reaching the feed (signed out
 * the whole time) skips the question — harmless, the order is still in Settings; a restore
 * onto a new device (firstInstall == lastUpdate) is asked once.
 *
 * The rate prompt's usage counters (first launch seen, distinct launch days) live here too:
 * they describe this install, never leave the device, and survive logout like the flags.
 */
class OnboardingStore(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore = appContext.onboardingDataStore

    /**
     * True once the first-run "how do you want to read?" question was answered or dismissed,
     * or the install predates the question (see the class KDoc on upgrade seeding). The seed
     * is resolved before the first emission, so an upgrading user never sees a `false`.
     */
    val feedOrderChosen: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FEED_ORDER_CHOSEN] ?: run {
            if (prefs[KEY_INSTALL_SEEDED] == true) return@run false
            val existing = isUpgradedInstall()
            dataStore.edit { edit ->
                edit[KEY_INSTALL_SEEDED] = true
                if (existing) edit[KEY_FEED_ORDER_CHOSEN] = true
            }
            existing
        }
    }

    /** True once the Channels swipe-to-hide hint was dismissed or the gesture was used. */
    val channelsSwipeHintSeen: Flow<Boolean> = flag(KEY_CHANNELS_SWIPE_HINT_SEEN)

    /** True once the split-button caption was shown. */
    val splitButtonHintSeen: Flow<Boolean> = flag(KEY_SPLIT_BUTTON_HINT_SEEN)

    /**
     * True once the "read without joining" delay explanation was dismissed — shown after the
     * first successful follow, since a followed channel's new posts arrive by polling.
     */
    val followDelayTipSeen: Flow<Boolean> = flag(KEY_FOLLOW_DELAY_TIP_SEEN)

    /** Whether the rate-on-Google-Play sheet may appear now. See [RatePromptPolicy]. */
    val ratePromptEligible: Flow<Boolean> = dataStore.data.map { prefs ->
        RatePromptPolicy.isEligible(
            nowMs = System.currentTimeMillis(),
            firstSeenMs = prefs[KEY_RATE_FIRST_SEEN_MS],
            distinctDays = prefs[KEY_RATE_DAY_COUNT] ?: 0,
            shown = prefs[KEY_RATE_PROMPT_SHOWN] ?: false,
            installedFromPlay = installedFromPlay,
        )
    }.flowOn(Dispatchers.Default)

    // The installer never changes while the process lives; one PackageManager call is enough.
    private val installedFromPlay: Boolean by lazy { RatePromptPolicy.isInstalledFromPlay(appContext) }

    /**
     * Counts today as a launch day for the rate prompt and stamps the first launch the first
     * time it runs. Called once per process start, off the main thread.
     */
    suspend fun recordLaunchDay(nowMs: Long = System.currentTimeMillis(), today: Long = LocalDate.now().toEpochDay()) {
        dataStore.edit { prefs ->
            if (prefs[KEY_RATE_FIRST_SEEN_MS] == null) prefs[KEY_RATE_FIRST_SEEN_MS] = nowMs
            val last = prefs[KEY_RATE_LAST_DAY]
            if (last != today) {
                prefs[KEY_RATE_DAY_COUNT] = RatePromptPolicy.nextDayCount(prefs[KEY_RATE_DAY_COUNT] ?: 0, last, today)
                prefs[KEY_RATE_LAST_DAY] = today
            }
        }
    }

    suspend fun setRatePromptShown() = set(KEY_RATE_PROMPT_SHOWN, true)

    suspend fun setFeedOrderChosen(value: Boolean = true) = set(KEY_FEED_ORDER_CHOSEN, value)

    suspend fun setChannelsSwipeHintSeen(value: Boolean = true) = set(KEY_CHANNELS_SWIPE_HINT_SEEN, value)

    suspend fun setSplitButtonHintSeen(value: Boolean = true) = set(KEY_SPLIT_BUTTON_HINT_SEEN, value)

    suspend fun setFollowDelayTipSeen(value: Boolean = true) = set(KEY_FOLLOW_DELAY_TIP_SEEN, value)

    private fun isUpgradedInstall(): Boolean = runCatching {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(appContext.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(appContext.packageName, 0)
        }
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

    private fun flag(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[key] ?: false }

    private suspend fun set(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    private companion object {
        val KEY_FEED_ORDER_CHOSEN = booleanPreferencesKey("feed_order_chosen")
        val KEY_CHANNELS_SWIPE_HINT_SEEN = booleanPreferencesKey("channels_swipe_hint_seen")
        val KEY_SPLIT_BUTTON_HINT_SEEN = booleanPreferencesKey("split_button_hint_seen")
        val KEY_FOLLOW_DELAY_TIP_SEEN = booleanPreferencesKey("follow_delay_tip_seen")
        val KEY_INSTALL_SEEDED = booleanPreferencesKey("install_seeded")
        val KEY_RATE_FIRST_SEEN_MS = longPreferencesKey("rate_first_seen_ms")
        val KEY_RATE_DAY_COUNT = intPreferencesKey("rate_launch_day_count")
        val KEY_RATE_LAST_DAY = longPreferencesKey("rate_last_launch_day")
        val KEY_RATE_PROMPT_SHOWN = booleanPreferencesKey("rate_prompt_shown")
    }
}

private val Context.onboardingDataStore by preferencesDataStore(
    name = "onboarding",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
