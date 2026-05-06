package dev.lyo.hortay.data.web

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
 * Persistent list of channel usernames the user has subscribed to in anonymous mode.
 *
 * Why a separate store from TDLib's chat list:
 *   - Anonymous mode has no Telegram session, so there's no server-side notion of
 *     "your channels". We track the user's intent locally.
 *   - When a user signs in (Phase 2 step F: anonymous → authenticated migration),
 *     this set becomes the input list for the auto-subscribe flow that issues
 *     TdApi.SearchPublicChat + TdApi.JoinChat per username (throttled to avoid
 *     FLOOD_WAIT). Until that runs, both stores can coexist for the same username.
 *
 * Storage shape: a `Set<String>` of bare usernames (no `@`, no URL). Bare so that
 * write callers don't have to remember the canonical form — [parseUsernameFromInput]
 * normalizes any user input before it lands here. The set is intentionally small —
 * realistic upper bound ~200 channels (above which we recommend signing in for the
 * fuller TDLib experience).
 *
 * `ImmutableSet` exposed to consumers so Compose stability inference treats the
 * collection as a value rather than a mutable reference.
 */
class SubscriptionsStore(context: Context) {

    private val dataStore = context.applicationContext.subscriptionsDataStore

    val subscriptions: Flow<ImmutableSet<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SUBSCRIPTIONS]?.toPersistentSet() ?: persistentSetOf()
    }

    suspend fun add(username: String) {
        val normalized = normalize(username) ?: return
        dataStore.edit { prefs ->
            val current = prefs[KEY_SUBSCRIPTIONS] ?: emptySet()
            prefs[KEY_SUBSCRIPTIONS] = current + normalized
        }
    }

    suspend fun remove(username: String) {
        val normalized = normalize(username) ?: return
        dataStore.edit { prefs ->
            val current = prefs[KEY_SUBSCRIPTIONS] ?: emptySet()
            prefs[KEY_SUBSCRIPTIONS] = current - normalized
        }
    }

    /**
     * Canonical username form: trimmed, no leading `@`, lowercase. Telegram handles
     * are case-insensitive on the server side, so `@Durov` and `@durov` are the same
     * channel — but a plain `Set<String>` will happily store both as distinct entries
     * (and a later `add(@Durov)` won't dedupe against an earlier `add(@durov)`).
     * Normalising at the store boundary makes the case-insensitivity invariant
     * explicit. Matches the bare-username shape returned by [parseUsernameFromInput],
     * which the rest of the data layer already passes through unchanged.
     */
    private fun normalize(username: String): String? {
        val trimmed = username.trim().removePrefix("@").lowercase()
        return trimmed.ifEmpty { null }
    }

    private companion object {
        val KEY_SUBSCRIPTIONS = stringSetPreferencesKey("anon_subscriptions")
    }
}

private val Context.subscriptionsDataStore by preferencesDataStore(name = "anon_subscriptions")
