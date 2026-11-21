package dev.lyo.hortay.data.discover

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/** A source channel's similar-chat ids as Telegram returned them, and when (epoch ms). */
data class SimilarEntry(
    val similar: List<Long>,
    val fetchedAtMs: Long,
)

/**
 * Persisted `source chat id → SimilarEntry` cache behind "Recommended by your channels", so
 * reopening the add-channel sheet paints instantly and spends no `GetChatSimilarChats`
 * call on a source fetched within [RecommendedChannelsRepository]'s TTL. Account-bound:
 * wiped through `AppGraph.session.onEnd`. An interface so tests run without a Context.
 */
interface SimilarChatsStore {
    suspend fun load(): Map<Long, SimilarEntry>
    suspend fun put(source: Long, entry: SimilarEntry)

    /** Drop every entry whose source is not in [sources] (channels the user left). */
    suspend fun retain(sources: Set<Long>)
    suspend fun clear()
}

class SimilarChatsStoreImpl(context: Context) : SimilarChatsStore {

    private val dataStore = context.applicationContext.similarChatsDataStore

    override suspend fun load(): Map<Long, SimilarEntry> {
        val prefs = dataStore.data.first()
        val out = HashMap<Long, SimilarEntry>()
        for ((key, value) in prefs.asMap()) {
            val source = key.name.removePrefix(PREFIX).takeIf { key.name.startsWith(PREFIX) }
                ?.toLongOrNull() ?: continue
            decode(value as? String)?.let { out[source] = it }
        }
        return out
    }

    override suspend fun put(source: Long, entry: SimilarEntry) {
        dataStore.edit { it[keyOf(source)] = encode(entry) }
    }

    override suspend fun retain(sources: Set<Long>) {
        dataStore.edit { prefs ->
            val stale = prefs.asMap().keys.filter { key ->
                val id = key.name.removePrefix(PREFIX).toLongOrNull()
                key.name.startsWith(PREFIX) && (id == null || id !in sources)
            }
            stale.forEach { prefs.remove(it) }
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    internal companion object {
        private const val PREFIX = "src_"

        private fun keyOf(source: Long) = stringPreferencesKey("$PREFIX$source")

        /** `fetchedAtMs;id1,id2,…` — an empty list keeps its timestamp (`fetchedAtMs;`). */
        fun encode(entry: SimilarEntry): String =
            entry.fetchedAtMs.toString() + ";" + entry.similar.joinToString(",")

        fun decode(raw: String?): SimilarEntry? {
            if (raw == null) return null
            val at = raw.substringBefore(';').toLongOrNull() ?: return null
            val ids = raw.substringAfter(';', "").split(',').mapNotNull { it.toLongOrNull() }
            return SimilarEntry(ids, at)
        }
    }
}

private val Context.similarChatsDataStore by preferencesDataStore(
    name = "similar_chats",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
