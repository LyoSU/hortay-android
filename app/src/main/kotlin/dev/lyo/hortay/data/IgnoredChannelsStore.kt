package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

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
 *
 * Timed hides ("hide for a day") live in a second set of `chatId:untilEpochMillis`
 * entries. [ignored] is the union of the permanent set and the timed entries that have
 * not run out, so every consumer — both feed filters, the Channels tab, the Hidden
 * channels screen — treats a snoozed channel exactly like a hidden one and needs no
 * second code path. The flow re-emits by itself when an entry runs out.
 */
class IgnoredChannelsStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val dataStore = context.applicationContext.ignoredChannelsDataStore

    /**
     * Persistent set of hidden chat ids. `ImmutableSet` exposed to consumers so
     * Compose stability inference treats the collection as a value and the
     * derived feed flow doesn't trigger spurious recompositions on equal sets.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ignored: Flow<ImmutableSet<Long>> = dataStore.data
        .map { prefs ->
            val permanent = prefs[KEY_IGNORED]?.mapNotNullTo(HashSet()) { it.toLongOrNull() } ?: emptySet()
            permanent to parseSnoozes(prefs[KEY_SNOOZED])
        }
        .distinctUntilChanged()
        .transformLatest { (permanent, snoozes) ->
            while (true) {
                val at = now()
                emit(hiddenAt(permanent, snoozes, at))
                val nextExpiry = snoozes.values.filter { it > at }.minOrNull() ?: break
                // Re-checked at least once a minute rather than slept through in one go:
                // `delay` runs on a clock that stops in deep sleep, the expiry is wall time.
                delay((nextExpiry - at + 1).coerceAtMost(SNOOZE_RECHECK_MS))
            }
        }
        .distinctUntilChanged()

    /**
     * Hide [chatId] until [untilEpochMillis]. A channel that is already hidden for good
     * stays that way — the permanent entry wins in [ignored].
     */
    suspend fun snooze(chatId: Long, untilEpochMillis: Long) {
        if (chatId == 0L) return
        dataStore.edit { prefs ->
            val live = parseSnoozes(prefs[KEY_SNOOZED]).filterValues { it > now() }
            prefs[KEY_SNOOZED] = encodeSnoozes(live + (chatId to untilEpochMillis))
        }
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
            dropSnoozes(prefs, setOf(chatId))
        }
    }

    /** Hide every id in [chatIds] at once — the "Hide all from feed" bulk action. */
    suspend fun addAll(chatIds: Collection<Long>) {
        val keys = chatIds.filter { it != 0L }.map(Long::toString)
        if (keys.isEmpty()) return
        dataStore.edit { prefs -> prefs[KEY_IGNORED] = (prefs[KEY_IGNORED] ?: emptySet()) + keys }
    }

    /**
     * Un-hide every id in [chatIds] at once — the "Show all in feed" bulk action. Scoped to
     * the caller's ids rather than wiping the set, because the store is shared with guest
     * mode and its hidden channels must survive a signed-in "Show all".
     */
    suspend fun removeAll(chatIds: Collection<Long>) {
        val keys = chatIds.map(Long::toString).toSet()
        if (keys.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY_IGNORED] = (prefs[KEY_IGNORED] ?: emptySet()) - keys
            dropSnoozes(prefs, chatIds.toSet())
        }
    }

    suspend fun toggle(chatId: Long): Boolean {
        if (chatId == 0L) return false
        var resultingState = false
        dataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            val key = chatId.toString()
            val snoozed = (parseSnoozes(prefs[KEY_SNOOZED])[chatId] ?: 0L) > now()
            if (key in current || snoozed) {
                prefs[KEY_IGNORED] = current - key
                dropSnoozes(prefs, setOf(chatId))
                resultingState = false
            } else {
                prefs[KEY_IGNORED] = current + key
                resultingState = true
            }
        }
        return resultingState
    }

    private fun dropSnoozes(prefs: androidx.datastore.preferences.core.MutablePreferences, chatIds: Set<Long>) {
        val snoozes = parseSnoozes(prefs[KEY_SNOOZED])
        if (snoozes.isEmpty()) return
        prefs[KEY_SNOOZED] = encodeSnoozes(snoozes.filter { (id, until) -> id !in chatIds && until > now() })
    }

    internal companion object {
        private val KEY_IGNORED = stringSetPreferencesKey("ignored_channel_ids")
        private val KEY_SNOOZED = stringSetPreferencesKey("snoozed_channel_ids")
        private const val SNOOZE_RECHECK_MS = 60_000L

        /** `chatId:untilEpochMillis` entries → map; malformed entries are skipped. */
        internal fun parseSnoozes(raw: Set<String>?): Map<Long, Long> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.mapNotNull { entry ->
                val id = entry.substringBefore(':').toLongOrNull()
                val until = entry.substringAfter(':', "").toLongOrNull()
                if (id != null && until != null) id to until else null
            }.toMap()
        }

        internal fun encodeSnoozes(snoozes: Map<Long, Long>): Set<String> =
            snoozes.mapTo(HashSet(snoozes.size)) { (id, until) -> "$id:$until" }

        /** The hidden set at wall time [at]: permanent hides plus snoozes still running. */
        internal fun hiddenAt(permanent: Set<Long>, snoozes: Map<Long, Long>, at: Long): ImmutableSet<Long> =
            (permanent + snoozes.filterValues { it > at }.keys).toPersistentSet()
    }
}

private val Context.ignoredChannelsDataStore by preferencesDataStore(
    name = "ignored_channels",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
