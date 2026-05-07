package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Persists a tiny "what was on the user's screen last time" snapshot so cold start
 * can show real content in <100ms instead of a blank feed for the multi-second
 * `refresh()` round-trip storm to land.
 *
 * Storage shape: just a flat list of `(chatId, messageId)` pairs — TDLib already
 * keeps the full message bodies on disk, and asking [TdApi.GetMessage] for ids it
 * has cached is essentially free (sub-millisecond, no network). Persisting the
 * full mapped [TimelinePost] tree would mean threading [@Serializable] through
 * every `PostContent` subtype and risking schema breakage on app updates; that
 * trade-off isn't worth it when the source of truth (TDLib's local DB) can
 * rebuild for us.
 *
 * Encoding: pipe-separated `chatId,messageId` pairs. JSON would work too but adds
 * an unnecessary parser dependency for two longs; the flat string is human-
 * readable in `adb shell` for debugging and trivially robust to corruption (one
 * bad entry doesn't poison the rest — see [load]'s mapNotNull).
 */
class TimelineSnapshotStore(context: Context) {

    private val dataStore = context.applicationContext.snapshotDataStore

    suspend fun load(): List<Pair<Long, Long>> {
        val packed = dataStore.data.first()[KEY].orEmpty()
        if (packed.isEmpty()) return emptyList()
        return packed.split(SEPARATOR_ENTRY).mapNotNull(::parseEntry)
    }

    suspend fun save(entries: List<Pair<Long, Long>>) {
        val packed = entries.joinToString(SEPARATOR_ENTRY.toString()) { (c, m) -> "$c$SEPARATOR_FIELD$m" }
        dataStore.edit { it[KEY] = packed }
    }

    /**
     * Drop the persisted snapshot. Called from [AppGraph] on logout so a
     * cold restart between sign-out and sign-in (e.g. user logs out, force-
     * stops the app, signs in to another account next launch) doesn't
     * paint the previous account's posts into the new account's view
     * during the brief restoreFromSnapshot → first-refresh-completes
     * window.
     */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private fun parseEntry(text: String): Pair<Long, Long>? {
        val parts = text.split(SEPARATOR_FIELD, limit = 2)
        if (parts.size != 2) return null
        val chatId = parts[0].toLongOrNull() ?: return null
        val messageId = parts[1].toLongOrNull() ?: return null
        return chatId to messageId
    }

    private companion object {
        val KEY = stringPreferencesKey("timeline_snapshot_v1")
        const val SEPARATOR_ENTRY = '|'
        const val SEPARATOR_FIELD = ','
    }
}

private val Context.snapshotDataStore by preferencesDataStore(name = "timeline_snapshot")
