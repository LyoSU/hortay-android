package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * One post as [StoryClusterer] sees it: identity, channel, time and plain text. No TDLib or
 * Compose types, so the clustering is unit-testable on its own.
 *
 * @property key the feed row key ([feedKey]); the output is keyed by it.
 * @property version changes whenever [text] may have changed (the edit date), so the token
 *   cache re-tokenises an edited post instead of serving its old words.
 */
internal data class StoryPost(
    val key: String,
    val chatId: Long,
    val date: Long,
    val text: String,
    val version: Long = 0L,
)

/** Another channel's post about the same story. */
@Immutable
data class StoryPeer(val key: String, val chatId: Long)

/** Posts further apart than this never link, and a post never lists a peer outside it. */
internal const val STORY_WINDOW_MS = 24L * 60 * 60 * 1000

/** Weighted-Jaccard similarity two posts need to link (the measured reference value). */
internal const val STORY_SIMILARITY_THRESHOLD = 0.18

/**
 * Minimum summed IDF weight of the rare tokens two posts share. Keeps two short posts that
 * happen to share a couple of rare words (a place name and a number) from linking on a high
 * ratio over a tiny vocabulary. Reference value.
 */
internal const val STORY_MIN_SHARED_WEIGHT = 12.0

/**
 * Posts with fewer distinct tokens than this (after footer stripping) take no part at all.
 * "Air raid alert in Kyiv region" style one-liners are recurring templates: they share all
 * of their few words with every other alert, and would glue unrelated stories together
 * through the single-link pass. Six tokens is roughly one full sentence of news text.
 */
internal const val STORY_MIN_TOKENS = 6

/**
 * Fewest distinct channels (the post's own included) that make a story, each with text of
 * its own: forwards never reach the clusterer ([storyInputOf]).
 */
internal const val STORY_MIN_CHATS = 3

/** Bound on [StoryClusterer]'s per-post token cache. */
internal const val STORY_TOKEN_CACHE_SIZE = 4096

/**
 * A token is "rare" (an indexing key) when it occurs in at most this share of posts, or
 * [STORY_RARE_MIN_DF] posts, whichever is larger.
 */
private const val STORY_RARE_SHARE = 0.02

/**
 * Floor of the rare-token document-frequency cap. The reference used 3, which on a small
 * feed (100 posts → cap 3) drops every word of a story four channels covered. 8 keeps such
 * stories indexable; their IDF weight is already low, so the weighted similarity still
 * decides.
 */
private const val STORY_RARE_MIN_DF = 8

/** A line repeated in at least this share of one channel's posts is that channel's footer. */
private const val FOOTER_SHARE = 0.3

/** ...and in at least this many of them. */
private const val FOOTER_MIN_POSTS = 3

/** Lines this short are never treated as a footer (they carry no tokens anyway). */
private const val FOOTER_MIN_LINE_CHARS = 4

private const val STEM_CHARS = 6
private const val MIN_WORD_CHARS = 4
private const val MIN_NUMBER_CHARS = 2

/**
 * Words dropped before stemming: the reference's measured list (stems of very common
 * Ukrainian news words and channel boilerplate). IDF already discounts common words, so this
 * only removes words common enough to skew the rare-token index on small feeds.
 */
private val STOP_STEMS = setOf(
    "україн", "росій", "заявив", "повідом", "також", "через", "після", "може", "будуть",
    "можна", "якщо", "тому", "вони", "його", "цього", "коли", "який", "яка", "яке", "були",
    "було", "буде", "цьому", "щодо", "більше", "років", "році", "серед", "підпис", "підпиш",
    "канал", "новин", "telegr", "джерел", "читай", "дивіт",
)

private val URL_REGEX = Regex("""https?://\S+""")
private val WORD_REGEX = Regex("""[\p{L}\p{Nd}]+""")
private val YEAR_REGEX = Regex("""20\d\d""")

/**
 * Tokens of one line: lower-cased words of at least four characters cut to a six-character
 * stem (a cheap fold of Slavic inflection: "обстрілу" / "обстріли" → "обстрі"), plus numbers
 * of two or more digits prefixed with `#` so they never collide with a word. Years (20xx)
 * are dropped — every post of the year carries one. URLs are removed first.
 */
internal fun storyLineTokens(line: String): Set<String> {
    val out = HashSet<String>()
    val cleaned = URL_REGEX.replace(line.lowercase(), " ")
    for (match in WORD_REGEX.findAll(cleaned)) {
        val w = match.value
        if (w.all { it.isDigit() }) {
            if (w.length >= MIN_NUMBER_CHARS && !YEAR_REGEX.matches(w)) out += "#$w"
        } else if (w.length >= MIN_WORD_CHARS) {
            val stem = w.take(STEM_CHARS)
            if (stem !in STOP_STEMS) out += stem
        }
    }
    return out
}

/**
 * Per channel, the lines repeated across many of its posts — the signature, the "subscribe"
 * link, the donation footer. They are identical in every post of that channel and would
 * otherwise make all of its posts look alike. [postsLines] is each post's set of trimmed
 * lines; a line counts once per post.
 */
internal fun footerLines(postsLines: List<Pair<Long, Set<String>>>): Map<Long, Set<String>> {
    val counts = HashMap<Long, HashMap<String, Int>>()
    val postCount = HashMap<Long, Int>()
    for ((chatId, lines) in postsLines) {
        postCount[chatId] = (postCount[chatId] ?: 0) + 1
        val perChat = counts.getOrPut(chatId) { HashMap() }
        for (line in lines) {
            if (line.length >= FOOTER_MIN_LINE_CHARS) perChat[line] = (perChat[line] ?: 0) + 1
        }
    }
    val result = HashMap<Long, Set<String>>()
    for ((chatId, perChat) in counts) {
        val need = max(FOOTER_MIN_POSTS.toDouble(), (postCount[chatId] ?: 0) * FOOTER_SHARE)
        val footer = perChat.filterValues { it >= need }.keys
        if (footer.isNotEmpty()) result[chatId] = footer
    }
    return result
}

/**
 * Tokens of a whole post with [footer] lines removed — the uncached composition of
 * [storyLineTokens] and [footerLines], for tests and one-off callers.
 */
internal fun storyTokens(text: String, footer: Set<String> = emptySet()): Set<String> {
    val out = HashSet<String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line in footer) continue
        out += storyLineTokens(line)
    }
    return out
}

/**
 * "Also covered by N of your channels": finds posts of DIFFERENT channels that report the
 * same story, by text alone. Measured on 748 real posts from 19 news channels over 24 h:
 * shared links and shared forwards find almost nothing, rare-token text similarity found 9
 * clusters of 3+ channels, all correct.
 *
 * Method (a port of the measured reference):
 *  1. Each post is cut into lines; a channel's footer lines ([footerLines]) are dropped and
 *     the rest tokenised ([storyLineTokens]). Posts with fewer than [STORY_MIN_TOKENS]
 *     tokens never link (they still count toward IDF).
 *  2. IDF is computed over the current post set. Only RARE tokens (document frequency 2 up
 *     to max([STORY_RARE_MIN_DF], 2 % of posts)) index posts; common words cannot pull two
 *     posts together, but still count in each post's total weight.
 *  3. Pairs sharing a rare token are scored with weighted Jaccard:
 *     `shared / (weight(A) + weight(B) - shared)`, where weights are summed IDF. A pair links
 *     when it scores at least [STORY_SIMILARITY_THRESHOLD], shares at least
 *     [STORY_MIN_SHARED_WEIGHT], comes from two different chats and is at most
 *     [STORY_WINDOW_MS] apart.
 *  4. A post's peers are the posts it links to, plus THEIR links (two hops), kept only when
 *     inside the window of the post itself, one per other chat. It is annotated only when
 *     those peers span at least `STORY_MIN_CHATS - 1` other chats.
 *
 * Why two hops and not the whole single-link component: single-link clustering chains —
 * A~B on one story, B~C on another (a roundup post mentioning both), and C ends up listed
 * under A. Every hop already needs a strong match, so two hops still covers a story
 * re-worded by each channel (A and C both close to B but not to each other), while a chain
 * across three stories never reaches. The window is re-checked against the post itself, not
 * only per link, so a chain cannot walk a story across days either.
 *
 * Posts of the same chat never link and never count toward [STORY_MIN_CHATS]: five posts
 * from two channels are not a story covered by three.
 *
 * Tokenisation is cached per post (key + [StoryPost.version] + text hash, LRU-bounded by
 * [STORY_TOKEN_CACHE_SIZE]), so a live update re-tokenises only the posts that changed. The
 * pass itself (footers, IDF, pairs) re-runs over the whole set, because one new post shifts
 * the IDF of every word it contains.
 *
 * Not thread-safe: call [cluster] from one coroutine at a time (the cache is mutable).
 */
internal class StoryClusterer(private val cacheSize: Int = STORY_TOKEN_CACHE_SIZE) {

    private class CachedPost(val version: Long, val textHash: Int, val lines: List<Pair<String, Set<String>>>)

    private val cache = object : LinkedHashMap<String, CachedPost>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedPost>?): Boolean =
            size > cacheSize
    }

    /** Drops every cached post (logout: the next account shares nothing with this one). */
    fun clear() {
        cache.clear()
    }

    /** Number of posts tokenised (cache misses) so far; for tests. */
    internal var tokenisedCount: Int = 0
        private set

    private fun linesOf(post: StoryPost): List<Pair<String, Set<String>>> {
        val hash = post.text.hashCode()
        val hit = cache[post.key]
        if (hit != null && hit.version == post.version && hit.textHash == hash) return hit.lines
        tokenisedCount++
        val lines = post.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { it to storyLineTokens(it) }
            // A line without tokens can neither match nor, as a footer, remove anything.
            .filter { it.second.isNotEmpty() }
            .toList()
        cache[post.key] = CachedPost(post.version, hash, lines)
        return lines
    }

    /**
     * Story peers of every post in [input] that belongs to a story of at least
     * [STORY_MIN_CHATS] channels: post key → the other channels' posts, one per chat, oldest
     * first. Posts without a story are absent. The result does not depend on input order.
     */
    fun cluster(input: List<StoryPost>): Map<String, List<StoryPeer>> {
        val posts = input.distinctBy { it.key }.sortedBy { it.key }
        if (posts.size < STORY_MIN_CHATS) return emptyMap()
        val lines = posts.map { linesOf(it) }

        // 1. Footers per chat, then each post's token set without them.
        val footers = footerLines(posts.indices.map { i -> posts[i].chatId to lines[i].mapTo(HashSet()) { it.first } })
        val tokens = posts.indices.map { i ->
            val footer = footers[posts[i].chatId].orEmpty()
            val out = HashSet<String>()
            for ((line, lineTokens) in lines[i]) if (line !in footer) out += lineTokens
            out
        }
        if (posts.indices.count { tokens[it].size >= STORY_MIN_TOKENS } < STORY_MIN_CHATS) return emptyMap()

        // 2. IDF over EVERY post (short ones included: they are part of what makes a word
        // common, and dropping them would shrink every weight and starve the absolute
        // [STORY_MIN_SHARED_WEIGHT] floor). Only long enough posts are indexed.
        val n = tokens.count { it.isNotEmpty() }
        val df = HashMap<String, Int>()
        for (set in tokens) for (w in set) df[w] = (df[w] ?: 0) + 1
        fun idf(w: String) = ln(n.toDouble() / (df[w] ?: 1))
        val rareCap = max(STORY_RARE_MIN_DF.toDouble(), n * STORY_RARE_SHARE)
        val weight = DoubleArray(posts.size)
        val index = HashMap<String, MutableList<Int>>()
        for (i in posts.indices) {
            if (tokens[i].size < STORY_MIN_TOKENS) continue
            var sum = 0.0
            for (w in tokens[i]) {
                val idfW = idf(w)
                sum += idfW
                val d = df[w] ?: 0
                if (d >= 2 && d <= rareCap) index.getOrPut(w) { ArrayList() } += i
            }
            weight[i] = sum
        }

        // 3. Shared rare weight per cross-chat pair inside the window, then the links.
        val shared = HashMap<Long, Double>()
        for ((w, list) in index) {
            val idfW = idf(w)
            for (x in 0 until list.size) {
                for (y in x + 1 until list.size) {
                    val a = list[x]
                    val b = list[y]
                    val candidate = posts[a].chatId != posts[b].chatId &&
                        abs(posts[a].date - posts[b].date) <= STORY_WINDOW_MS
                    if (candidate) {
                        val pair = a.toLong() * posts.size + b
                        shared[pair] = (shared[pair] ?: 0.0) + idfW
                    }
                }
            }
        }
        val links = HashMap<Int, MutableSet<Int>>()
        for ((pair, s) in shared) {
            val a = (pair / posts.size).toInt()
            val b = (pair % posts.size).toInt()
            val similarity = s / (weight[a] + weight[b] - s)
            if (similarity >= STORY_SIMILARITY_THRESHOLD && s >= STORY_MIN_SHARED_WEIGHT) {
                links.getOrPut(a) { HashSet() } += b
                links.getOrPut(b) { HashSet() } += a
            }
        }

        // 4. Peers within two hops and the post's own window, one per other chat.
        val result = HashMap<String, List<StoryPeer>>()
        for ((i, direct) in links) {
            val self = posts[i]
            val reach = HashSet<Int>(direct)
            for (j in direct) links[j]?.let { reach += it }
            reach -= i
            val peers = reach
                .filter { posts[it].chatId != self.chatId && abs(posts[it].date - self.date) <= STORY_WINDOW_MS }
                .sortedWith(compareBy<Int>({ posts[it].date }, { posts[it].key }))
                .distinctBy { posts[it].chatId }
            if (peers.size >= STORY_MIN_CHATS - 1) {
                result[self.key] = peers.map { StoryPeer(posts[it].key, posts[it].chatId) }
            }
        }
        return result
    }
}

/**
 * A [StoryClusterer] whose cache belongs to one account session, resettable from the main
 * thread without ever waiting on a running pass.
 *
 * A pass over a big feed is long and not cancellable, and it holds this object's monitor
 * for its whole length (the monitor keeps the clusterer's cache single-threaded). Taking
 * that monitor from the main thread to clear the cache at logout could freeze the UI for
 * the rest of the pass. Instead [reset] only bumps an atomic generation: the next pass of
 * the new generation clears the cache under the monitor before it runs, and a pass captured
 * under an older generation returns null — before it starts or after it finishes —
 * so the previous account's peers are never published.
 */
internal class SessionStoryClusterer(private val clusterer: StoryClusterer = StoryClusterer()) {

    private val generation = AtomicLong(0L)

    // Generation the cache was filled under; guarded by this object's monitor.
    private var cacheGeneration = 0L

    /** The current session generation; capture it together with the feed a pass will read. */
    val currentGeneration: Long get() = generation.get()

    /** Start a new session generation and return it. Never blocks; callable from any thread. */
    fun reset(): Long = generation.incrementAndGet()

    /**
     * Story peers of [input] (see [StoryClusterer.cluster]), or null when [passGeneration] is
     * no longer current — the result belongs to a session that has ended and must be dropped.
     */
    fun cluster(input: List<StoryPost>, passGeneration: Long): Map<String, List<StoryPeer>>? =
        synchronized(this) {
            if (passGeneration != generation.get()) return@synchronized null
            if (cacheGeneration != passGeneration) {
                clusterer.clear()
                cacheGeneration = passGeneration
            }
            val peers = clusterer.cluster(input)
            peers.takeIf { passGeneration == generation.get() }
        }
}
