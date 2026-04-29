package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persistent set of bookmarked posts, keyed by `chatId/messageId`.
 *
 * Backed by a Preferences DataStore — async I/O, survives process death, no main-thread
 * blocking. The set is exposed as a [Flow] so that bookmarks toggle live across the UI
 * without an event bus.
 */
class BookmarkStore(context: Context) {

    private val dataStore = context.applicationContext.bookmarkDataStore

    val bookmarks: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY] ?: emptySet()
    }

    suspend fun toggle(post: TimelinePost) {
        val key = post.bookmarkKey()
        dataStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            prefs[KEY] = if (key in current) current - key else current + key
        }
    }

    private companion object {
        val KEY = stringSetPreferencesKey("bookmarks")
    }
}

private val Context.bookmarkDataStore by preferencesDataStore(name = "bookmarks")

fun TimelinePost.bookmarkKey(): String = "$chatId/$id"
