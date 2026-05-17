package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-curated set of channel chat ids whose posts should be hidden from the
 * main timeline. Survives across cold starts and across the auth ↔ guest
 * routing flip — the user's intent ("don't show me posts from this channel
 * in the home feed") is a preference, not session state.
 *
 * Single key space: `Long` chat id. Works in TDLib mode (real supergroup ids
 * like `-1001234567890`) AND in guest mode, where [dev.lyo.hortay.data.web.WebPostAdapter.stableChatId]
 * deterministically derives a `Long` from a username via hash + supergroup
 * offset. A user who hides a channel in TDLib mode and later switches into
 * guest mode (or vice versa) sees the same channel filtered in both, since
 * the same handle maps to the same chat id across modes.
 *
 * Scope: the filter only applies to the main feed surfaces — both
 * [PostsRepository.subscribedPosts] (TDLib) and the equivalent
 * [dev.lyo.hortay.data.web.WebFeedSource.posts] (guest). Explicit user intent
 * still wins: tapping a channel header, drilling into the channel via the
 * channels list, opening a deep link, or scrolling comments on a hidden
 * channel's post all bypass the filter. We hide noise from the merged feed,
 * we do not delete content.
 *
 * Filter applied at the read side (combine on the merged-feed flow), not at
 * ingest. Un-hiding a channel must surface previously-arrived posts
 * immediately without a refresh round-trip — dropping at ingest would force
 * a pull-to-refresh after every un-hide, which is a worse UX than the
 * trivial filter cost on a ~1000-row PersistentList.
 *
 * Storage: `Set<String>` of decimal chat-id strings. We serialise Longs as
 * strings because the DataStore Preferences API only ships set codecs for
 * String / Int / Long-as-scalar — no `longSetPreferencesKey`. Decimal is
 * portable across reinstalls and easy to inspect in DataStore tooling.
 */
class IgnoredChannelsStore(context: Context) {

    private val dataStore = context.applicationContext.ignoredChannelsDataStore

    /**
     * Persistent set of hidden chat ids. `ImmutableSet` exposed to consumers so
     * Compose stability inference treats the collection as a value and the
     * derived feed flow doesn't trigger spurious recompositions on equal sets.
     */
    val ignored: Flow<ImmutableSet<Long>> = dataStore.data.map { prefs ->
        prefs[KEY_IGNORED]?.mapNotNull { it.toLongOrNull() }?.toPersistentSet() ?: persistentSetOf()
    }

    suspend fun add(chatId: Long) {
        if (chatId == 0L) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            prefs[KEY_IGNORED] = current + chatId.toString()
        }
    }

    suspend fun remove(chatId: Long) {
        if (chatId == 0L) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            prefs[KEY_IGNORED] = current - chatId.toString()
        }
    }

    suspend fun toggle(chatId: Long): Boolean {
        if (chatId == 0L) return false
        var resultingState = false
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            val key = chatId.toString()
            if (key in current) {
                prefs[KEY_IGNORED] = current - key
                resultingState = false
            } else {
                prefs[KEY_IGNORED] = current + key
                resultingState = true
            }
        }
        return resultingState
    }

    private companion object {
        val KEY_IGNORED = stringSetPreferencesKey("ignored_channel_ids")
    }
}

private val Context.ignoredChannelsDataStore by preferencesDataStore(name = "ignored_channels")
