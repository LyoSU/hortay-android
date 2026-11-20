package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.IdentityHashMap

/**
 * Words and hashtags the reader does not want in the merged feed. A post whose text
 * contains any of them is left out of the feed in both modes — the same read-side,
 * feed-only scope as [IgnoredChannelsStore]: opening the channel itself still shows
 * everything, and removing a word brings its posts back without a refresh.
 *
 * Entries are stored trimmed and lower-cased; matching is a case-insensitive substring
 * test (see [MutedWordsFilter]).
 */
class MutedWordsStore(context: Context) {

    private val dataStore = context.applicationContext.mutedWordsDataStore

    val words: Flow<ImmutableSet<String>> = dataStore.data
        .map { prefs -> prefs[KEY_WORDS]?.toPersistentSet() ?: persistentSetOf() }
        .distinctUntilChanged()

    /** Adds [raw]; returns false when it is too short to be a usable filter. */
    suspend fun add(raw: String): Boolean {
        val word = normalize(raw) ?: return false
        dataStore.edit { prefs -> prefs[KEY_WORDS] = (prefs[KEY_WORDS] ?: emptySet()) + word }
        return true
    }

    suspend fun remove(word: String) {
        dataStore.edit { prefs -> prefs[KEY_WORDS] = (prefs[KEY_WORDS] ?: emptySet()) - word }
    }

    companion object {
        private val KEY_WORDS = stringSetPreferencesKey("muted_words")

        /** Shortest entry accepted: a single letter would match nearly every post. */
        const val MIN_WORD_LENGTH = 2
        const val MAX_WORD_LENGTH = 64

        /** Trimmed, lower-cased, inner whitespace collapsed; null when unusable. */
        fun normalize(raw: String): String? {
            val word = raw.trim().lowercase().replace(WHITESPACE, " ")
            return word.takeIf { it.length in MIN_WORD_LENGTH..MAX_WORD_LENGTH }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

private val Context.mutedWordsDataStore by preferencesDataStore(
    name = "muted_words",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Drops the posts whose text contains a muted word.
 *
 * A substring test, not a whole-word one: Ukrainian, Russian and Polish inflect, so a
 * reader muting "реклам" expects "реклама" and "рекламу" gone too, and a hashtag is
 * matched the same way with its `#`.
 *
 * The feed flow re-emits on every counter tick with nearly all posts unchanged, so the
 * verdict is remembered per [PostContent] instance (posts are immutable and keep their
 * content object across emissions) and only new or edited posts are scanned again. The
 * memo is rebuilt on each pass, which bounds it to the posts currently in the feed.
 * Not thread-safe: one instance belongs to one flow collector.
 */
class MutedWordsFilter {

    private var memoWords: Set<String> = emptySet()
    private var memo = IdentityHashMap<PostContent, Boolean>()

    /**
     * Keys of the posts the last [apply] kept only because they are saved: a muted word
     * must not make a bookmark vanish from the Saved tab, so those rows stay in the
     * list and the feed drops them by key instead (see `PostsRepository.mutedSavedKeys`).
     */
    var savedOnly: PersistentSet<String> = persistentSetOf()
        private set

    fun apply(
        posts: List<TimelinePost>,
        words: Set<String>,
        savedKeys: Set<String> = emptySet(),
    ): List<TimelinePost> {
        if (words.isEmpty()) {
            if (memo.isNotEmpty()) memo = IdentityHashMap()
            memoWords = words
            savedOnly = persistentSetOf()
            return posts
        }
        val previous = if (words == memoWords) memo else null
        val next = IdentityHashMap<PostContent, Boolean>(posts.size)
        var anyMuted = false
        for (post in posts) {
            val content = post.content
            val muted = previous?.get(content) ?: isMuted(content.captionPlain, words)
            next[content] = muted
            if (muted) anyMuted = true
        }
        memo = next
        memoWords = words
        if (!anyMuted) {
            savedOnly = persistentSetOf()
            return posts
        }
        val kept = HashSet<String>()
        val out = posts.filter { post ->
            if (next[post.content] != true) return@filter true
            val key = post.bookmarkKey()
            (key in savedKeys).also { saved -> if (saved) kept += key }
        }
        savedOnly = if (kept.isEmpty()) persistentSetOf() else kept.toPersistentSet()
        return out
    }

    companion object {
        fun isMuted(text: String, words: Set<String>): Boolean =
            text.isNotEmpty() && words.any { text.contains(it, ignoreCase = true) }
    }
}
