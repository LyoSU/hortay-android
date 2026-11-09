package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One-shot "has the user already been shown this?" flags for feature-discovery UI.
 *
 * These are device-level UX flags, not account data: a second account signing in on the
 * same install has already learned the gestures, so nothing here is wiped on logout and no
 * `AppGraph.session.onEnd` hook is registered. All flags default to false and only ever
 * flip to true.
 */
class OnboardingStore(context: Context) {

    private val dataStore = context.applicationContext.onboardingDataStore

    /** True once the first-run "how do you want to read?" question was answered or dismissed. */
    val feedOrderChosen: Flow<Boolean> = flag(KEY_FEED_ORDER_CHOSEN)

    /** True once the Channels swipe-to-hide hint was dismissed or the gesture was used. */
    val channelsSwipeHintSeen: Flow<Boolean> = flag(KEY_CHANNELS_SWIPE_HINT_SEEN)

    /** True once the split-button caption was shown. */
    val splitButtonHintSeen: Flow<Boolean> = flag(KEY_SPLIT_BUTTON_HINT_SEEN)

    suspend fun setFeedOrderChosen(value: Boolean = true) = set(KEY_FEED_ORDER_CHOSEN, value)

    suspend fun setChannelsSwipeHintSeen(value: Boolean = true) = set(KEY_CHANNELS_SWIPE_HINT_SEEN, value)

    suspend fun setSplitButtonHintSeen(value: Boolean = true) = set(KEY_SPLIT_BUTTON_HINT_SEEN, value)

    private fun flag(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[key] ?: false }

    private suspend fun set(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    private companion object {
        val KEY_FEED_ORDER_CHOSEN = booleanPreferencesKey("feed_order_chosen")
        val KEY_CHANNELS_SWIPE_HINT_SEEN = booleanPreferencesKey("channels_swipe_hint_seen")
        val KEY_SPLIT_BUTTON_HINT_SEEN = booleanPreferencesKey("split_button_hint_seen")
    }
}

private val Context.onboardingDataStore by preferencesDataStore(
    name = "onboarding",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
