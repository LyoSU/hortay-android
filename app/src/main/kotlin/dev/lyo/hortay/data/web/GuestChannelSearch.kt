package dev.lyo.hortay.data.web

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * Offline channel search for guest mode.
 *
 * Without an account there is no way to search Telegram by name: TDLib is unauthorised
 * (no `searchPublicChats`) and t.me only answers for an exact `t.me/s/<username>`. The
 * honest substitute is to search everything the device already knows — channels the
 * reader follows or followed, the curated catalog, and channels that the followed ones
 * forward from or @mention — and to probe the typed text as an exact username on the
 * side (that part lives in the Add-channel sheet).
 *
 * [GuestChannelIndex.build] folds every field once, so [GuestChannelIndex.search] is a
 * handful of `startsWith` / `contains` calls per channel and runs on every keystroke
 * over a few thousand channels without a debounce.
 *
 * Matching folds case (`Locale.ROOT`), accents (NFD + combining marks stripped, which
 * also turns `ё` into `е`), and treats every run of non-letter, non-digit characters
 * (spaces, `_`, punctuation, emoji) as one word break. A query typed in the wrong
 * keyboard layout (`ghbdsn` for `привіт`, `руддщ` for `hello`) is tried as well and
 * ranks just below the same match typed correctly.
 */

/**
 * Where the device learned about a channel. Declaration order is the priority for
 * display data (title, avatar, …) when several sources know the same channel.
 */
enum class GuestChannelSource {
    /** A `channel` row the reader follows. */
    Subscribed,

    /** A `channel` row the reader no longer follows (unsubscribed, or looked up earlier). */
    Known,

    /** Fresh card data fetched from t.me/s this session (hydration). Carries no rank weight. */
    Live,

    /** A `channel_suggestion` row from past lookups. */
    History,

    /** The curated suggestions catalog. */
    Catalog,

    /** A forward source in the followed channels' posts. */
    Forwarded,

    /** An `@username` mention in the followed channels' posts. */
    Mentioned,
}

/** One piece of knowledge about a channel; [GuestChannelIndex.build] merges them by username. */
data class GuestChannelCandidate(
    val username: String,
    val title: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val subscribers: String? = null,
    val source: GuestChannelSource,
    /** How often the channel was seen, for [GuestChannelSource.Forwarded] / [GuestChannelSource.Mentioned]. */
    val count: Int = 1,
)

/** A merged, display-ready channel. [username] is lowercase. */
@Immutable
data class GuestChannel(
    val username: String,
    val title: String?,
    val description: String?,
    val avatarUrl: String?,
    val subscribers: String?,
    val isSubscribed: Boolean,
    private val sourceMask: Int,
    val forwardCount: Int,
    val mentionCount: Int,
) {
    fun hasSource(source: GuestChannelSource): Boolean = sourceMask and (1 shl source.ordinal) != 0

    /** Tie-break weight: followed / known > history > catalog > forward count > mention count. */
    val weight: Long =
        (if (hasSource(GuestChannelSource.Subscribed)) WEIGHT_SUBSCRIBED else 0L) +
            (if (hasSource(GuestChannelSource.Known)) WEIGHT_KNOWN else 0L) +
            (if (hasSource(GuestChannelSource.History)) WEIGHT_HISTORY else 0L) +
            (if (hasSource(GuestChannelSource.Catalog)) WEIGHT_CATALOG else 0L) +
            forwardCount.coerceIn(0, COUNT_CAP) * WEIGHT_PER_FORWARD +
            mentionCount.coerceIn(0, COUNT_CAP)

    private companion object {
        const val WEIGHT_SUBSCRIBED = 8_000_000L
        const val WEIGHT_KNOWN = 4_000_000L
        const val WEIGHT_HISTORY = 2_000_000L
        const val WEIGHT_CATALOG = 1_000_000L
        const val WEIGHT_PER_FORWARD = 1_000L
        const val COUNT_CAP = 999
    }
}

/** How a channel matched, best first. */
enum class GuestMatchTier {
    ExactUsername,
    UsernamePrefix,
    TitleWordPrefix,
    TitleSubstring,
    UsernameSubstring,
    DescriptionSubstring,
}

/**
 * One search result. [titleRanges] index into [GuestChannel.title], [usernameRanges]
 * into [GuestChannel.username] — the matched parts, for the row to emphasise.
 */
@Immutable
data class GuestChannelHit(
    val channel: GuestChannel,
    val tier: GuestMatchTier,
    val viaLayoutSwap: Boolean,
    val titleRanges: ImmutableList<IntRange>,
    val usernameRanges: ImmutableList<IntRange>,
)

/**
 * A bot's username. Telegram requires every bot username to end in "bot" (any case),
 * and no channel may take one that does; only Telegram's own service bots predate the
 * rule ([SERVICE_BOT_USERNAMES]). Mentions of bots are common in posts but can never be
 * followed, so they never become suggestions. Other handles that aren't channels
 * (people, groups) can't be told from the name; a t.me/s/ lookup sorts those out.
 */
fun isBotUsername(username: String): Boolean {
    val bare = username.removePrefix("@")
    return bare.endsWith("bot", ignoreCase = true) || bare.lowercase(Locale.ROOT) in SERVICE_BOT_USERNAMES
}

// Telegram's service and official inline bots whose usernames don't end in "bot".
private val SERVICE_BOT_USERNAMES = setOf(
    "botfather", "stickers", "telegraph", "gamee", "gif", "vid", "pic", "bing",
    "wiki", "imdb", "bold", "youtube", "music", "foursquare", "sticker", "vote", "like",
)

/**
 * The typed text as a username worth probing live on t.me: Telegram's public-username
 * shape (a Latin letter first, then letters, digits or `_`, 5–32 characters in all),
 * with an optional leading `@`. Shorter handles exist but are rare, and probing every
 * 2–4 letter prefix while the reader types would only spend requests. Lowercase, or null.
 */
fun probeableUsername(query: String): String? {
    val bare = query.trim().removePrefix("@")
    return if (PROBE_USERNAME_REGEX.matches(bare)) bare.lowercase(Locale.ROOT) else null
}

private val PROBE_USERNAME_REGEX = Regex("""[A-Za-z][A-Za-z0-9_]{4,31}""")

/** Immutable search index over merged [GuestChannel]s. Build once, search per keystroke. */
class GuestChannelIndex private constructor(
    private val entries: List<Entry>,
    private val byUsername: Map<String, GuestChannel>,
) {
    val size: Int get() = entries.size

    operator fun get(username: String): GuestChannel? = byUsername[username.lowercase(Locale.ROOT)]

    /** Ranked hits for [query], at most [limit]. Blank query → empty. */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<GuestChannelHit> {
        val variants = prepareQuery(query)
        if (variants.isEmpty()) return emptyList()
        val scored = ArrayList<Scored>()
        for (entry in entries) {
            var bestScore = Int.MAX_VALUE
            var bestVariant: PreparedQuery? = null
            var bestTier: GuestMatchTier? = null
            for (variant in variants) {
                val tier = matchTier(entry, variant) ?: continue
                val score = tier.ordinal * 2 + if (variant.swapped) 1 else 0
                if (score < bestScore) {
                    bestScore = score
                    bestVariant = variant
                    bestTier = tier
                }
            }
            if (bestVariant != null && bestTier != null) {
                scored += Scored(entry, bestScore, bestTier, bestVariant)
            }
        }
        scored.sortWith(SCORED_ORDER)
        return scored.asSequence().take(limit).map { s ->
            GuestChannelHit(
                channel = s.entry.channel,
                tier = s.tier,
                viaLayoutSwap = s.variant.swapped,
                titleRanges = s.entry.title?.let { rangesOf(it, s.variant) } ?: persistentListOf(),
                usernameRanges = rangesOf(s.entry.username, s.variant),
            )
        }.toList()
    }

    private class Scored(
        val entry: Entry,
        val score: Int,
        val tier: GuestMatchTier,
        val variant: PreparedQuery,
    )

    internal class Entry(
        val channel: GuestChannel,
        val title: FoldedText?,
        val username: FoldedText,
        val usernameCompact: String,
        val description: String?,
        val sortTitle: String,
    )

    companion object {
        const val DEFAULT_LIMIT = 30

        val EMPTY = GuestChannelIndex(emptyList(), emptyMap())

        /** Merge [candidates] by lowercase username and fold every searchable field once. */
        fun build(candidates: Iterable<GuestChannelCandidate>): GuestChannelIndex {
            val grouped = LinkedHashMap<String, MutableList<GuestChannelCandidate>>()
            for (c in candidates) {
                val key = c.username.trim().removePrefix("@").lowercase(Locale.ROOT)
                if (key.isEmpty()) continue
                grouped.getOrPut(key) { ArrayList(2) } += c
            }
            val entries = ArrayList<Entry>(grouped.size)
            val byUsername = HashMap<String, GuestChannel>(grouped.size * 2)
            for ((username, group) in grouped) {
                val channel = merge(username, group)
                byUsername[username] = channel
                entries += Entry(
                    channel = channel,
                    title = channel.title?.let(FoldedText::of)?.takeIf { it.text.isNotEmpty() },
                    username = FoldedText.of(username),
                    usernameCompact = username.filter { it.isLetterOrDigit() },
                    description = channel.description
                        ?.let { FoldedText.of(it).text }
                        ?.takeIf { it.isNotEmpty() },
                    sortTitle = (channel.title ?: username).lowercase(Locale.ROOT),
                )
            }
            return GuestChannelIndex(entries, byUsername)
        }

        private fun merge(username: String, group: List<GuestChannelCandidate>): GuestChannel {
            val ordered = group.sortedBy { it.source.ordinal }
            fun pick(field: (GuestChannelCandidate) -> String?): String? =
                ordered.firstNotNullOfOrNull { c -> field(c)?.trim()?.takeIf { it.isNotEmpty() } }
            var mask = 0
            var forwards = 0
            var mentions = 0
            for (c in group) {
                mask = mask or (1 shl c.source.ordinal)
                when (c.source) {
                    GuestChannelSource.Forwarded -> forwards += c.count.coerceAtLeast(0)
                    GuestChannelSource.Mentioned -> mentions += c.count.coerceAtLeast(0)
                    else -> Unit
                }
            }
            return GuestChannel(
                username = username,
                title = pick { it.title },
                description = pick { it.description },
                avatarUrl = pick { it.avatarUrl },
                subscribers = pick { it.subscribers },
                isSubscribed = mask and (1 shl GuestChannelSource.Subscribed.ordinal) != 0,
                sourceMask = mask,
                forwardCount = forwards,
                mentionCount = mentions,
            )
        }

        private val SCORED_ORDER = Comparator<Scored> { a, b ->
            when {
                a.score != b.score -> a.score.compareTo(b.score)
                a.entry.channel.weight != b.entry.channel.weight ->
                    b.entry.channel.weight.compareTo(a.entry.channel.weight)
                a.entry.sortTitle != b.entry.sortTitle -> a.entry.sortTitle.compareTo(b.entry.sortTitle)
                else -> a.entry.channel.username.compareTo(b.entry.channel.username)
            }
        }
    }
}

// ---- Matching ---------------------------------------------------------------

/** Below this length a token only matches at a word start (no infix noise). */
private const val MIN_INFIX_LEN = 2

/** Below this length a token never matches inside descriptions. */
private const val MIN_DESCRIPTION_LEN = 3

/** A folded query: separator-collapsed words, the same without breaks, and its words. */
internal class PreparedQuery(
    val spaced: String,
    val compact: String,
    val tokens: List<String>,
    val swapped: Boolean,
)

/** The query and its keyboard-layout swaps, folded and de-duplicated. Empty for a blank query. */
internal fun prepareQuery(raw: String): List<PreparedQuery> {
    val trimmed = raw.trim().removePrefix("@")
    if (trimmed.isBlank()) return emptyList()
    val out = ArrayList<PreparedQuery>(3)
    val seen = HashSet<String>()
    fun add(text: String, swapped: Boolean) {
        val folded = FoldedText.of(text).text
        if (folded.isEmpty() || !seen.add(folded)) return
        out += PreparedQuery(
            spaced = folded,
            compact = folded.replace(" ", ""),
            tokens = folded.split(' '),
            swapped = swapped,
        )
    }
    add(trimmed, swapped = false)
    for (alt in KeyboardLayouts.alternates(trimmed.lowercase(Locale.ROOT))) add(alt, swapped = true)
    return out
}

private fun matchTier(e: GuestChannelIndex.Entry, q: PreparedQuery): GuestMatchTier? {
    // Phrase-level username checks on the break-free forms, so "tech news" and
    // "technews" both find @tech_news.
    if (q.compact.isNotEmpty()) {
        if (e.usernameCompact == q.compact) return GuestMatchTier.ExactUsername
        if (e.usernameCompact.startsWith(q.compact)) return GuestMatchTier.UsernamePrefix
    }
    // Every token must match somewhere; the channel ranks by its weakest token.
    var worst = GuestMatchTier.ExactUsername
    for (token in q.tokens) {
        val tier = tokenTier(e, token) ?: return null
        if (tier > worst) worst = tier
    }
    return worst
}

private fun tokenTier(e: GuestChannelIndex.Entry, token: String): GuestMatchTier? {
    if (token.isEmpty()) return null
    if (e.usernameCompact.startsWith(token)) return GuestMatchTier.UsernamePrefix
    val title = e.title?.text
    if (title != null && hasWordPrefix(title, token)) return GuestMatchTier.TitleWordPrefix
    if (token.length >= MIN_INFIX_LEN) {
        if (title != null && title.contains(token)) return GuestMatchTier.TitleSubstring
        if (e.usernameCompact.contains(token) || e.username.text.contains(token)) {
            return GuestMatchTier.UsernameSubstring
        }
    }
    if (token.length >= MIN_DESCRIPTION_LEN && e.description?.contains(token) == true) {
        return GuestMatchTier.DescriptionSubstring
    }
    return null
}

private fun hasWordPrefix(text: String, token: String): Boolean {
    if (text.startsWith(token)) return true
    var from = 0
    while (true) {
        val i = text.indexOf(token, from)
        if (i < 0) return false
        if (i == 0 || text[i - 1] == ' ') return true
        from = i + 1
    }
}

/** Original-string ranges in [field] covered by the query (whole phrase, else each token). */
private fun rangesOf(field: FoldedText, q: PreparedQuery): ImmutableList<IntRange> {
    val folded = field.text
    val found = ArrayList<IntRange>()
    fun locate(needle: String, allowInfix: Boolean): Boolean {
        if (needle.isEmpty()) return false
        var at = -1
        var from = 0
        while (true) {
            val i = folded.indexOf(needle, from)
            if (i < 0) break
            if (i == 0 || folded[i - 1] == ' ') {
                at = i
                break
            }
            if (at < 0 && allowInfix) at = i
            from = i + 1
        }
        if (at < 0) return false
        found += field.originalRange(at, at + needle.length)
        return true
    }
    if (!locate(q.spaced, allowInfix = q.spaced.length >= MIN_INFIX_LEN)) {
        for (token in q.tokens) locate(token, allowInfix = token.length >= MIN_INFIX_LEN)
    }
    if (found.isEmpty()) return persistentListOf()
    found.sortBy { it.first }
    val merged = ArrayList<IntRange>(found.size)
    for (r in found) {
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) {
            merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
        } else {
            merged += r
        }
    }
    return merged.toImmutableList()
}

// ---- Folding ----------------------------------------------------------------

/**
 * [text] is the search form of a string: lowercase, accents stripped, every run of
 * non-letter / non-digit characters collapsed to one space, trimmed. Each folded char
 * remembers the original char span it came from, so a match maps back for highlighting.
 */
internal class FoldedText private constructor(
    val text: String,
    private val originStart: IntArray,
    private val originEnd: IntArray,
) {
    /** The original-string range for folded `[start, endExclusive)`. */
    fun originalRange(start: Int, endExclusive: Int): IntRange =
        originStart[start] until originEnd[endExclusive - 1]

    companion object {
        private val COMBINING_MARKS = Regex("""\p{Mn}+""")

        fun of(raw: String): FoldedText {
            val sb = StringBuilder(raw.length)
            var starts = IntArray(raw.length + 4)
            var ends = IntArray(raw.length + 4)
            fun push(c: Char, start: Int, end: Int) {
                if (sb.length == starts.size) {
                    starts = starts.copyOf(starts.size * 2)
                    ends = ends.copyOf(ends.size * 2)
                }
                starts[sb.length] = start
                ends[sb.length] = end
                sb.append(c)
            }
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                val n = Character.charCount(cp)
                val end = i + n
                if (cp < 0x80) {
                    val c = cp.toChar()
                    if (c.isLetterOrDigit()) {
                        push(c.lowercaseChar(), i, end)
                    } else if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                        push(' ', i, end)
                    }
                } else {
                    val folded = Normalizer
                        .normalize(String(Character.toChars(cp)).lowercase(Locale.ROOT), Normalizer.Form.NFD)
                        .replace(COMBINING_MARKS, "")
                    for (c in folded) {
                        if (c.isLetterOrDigit()) {
                            push(c, i, end)
                        } else if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                            push(' ', i, end)
                        }
                    }
                }
                i = end
            }
            var len = sb.length
            if (len > 0 && sb[len - 1] == ' ') len--
            return FoldedText(sb.substring(0, len), starts, ends)
        }
    }
}

// ---- Keyboard layouts -------------------------------------------------------

/**
 * QWERTY ↔ ЙЦУКЕН key-position maps (Ukrainian and Russian differ on four keys), for
 * queries typed with the wrong layout active.
 */
internal object KeyboardLayouts {
    private const val LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val UKRAINIAN = "йцукенгшщзхїфівапролджєячсмитьбюґ"
    private const val RUSSIAN = "йцукенгшщзхъфывапролджэячсмитьбюё"

    private val latinToUk = zipMap(LATIN, UKRAINIAN)
    private val latinToRu = zipMap(LATIN, RUSSIAN)
    private val cyrillicToLatin: Map<Char, Char> = zipMap(RUSSIAN, LATIN) + zipMap(UKRAINIAN, LATIN)

    private fun zipMap(from: String, to: String): Map<Char, Char> =
        from.indices.associate { from[it] to to[it] }

    /**
     * Layout-swapped versions of a lowercase [query]: Latin → Ukrainian and Russian
     * when it is typed in Latin letters, Cyrillic → Latin when it has Cyrillic ones.
     */
    fun alternates(query: String): List<String> {
        val hasCyrillic = query.any { it in cyrillicToLatin }
        val hasLatin = query.any { it in 'a'..'z' }
        val out = ArrayList<String>(2)
        if (hasCyrillic) out += swap(query, cyrillicToLatin)
        if (hasLatin && !hasCyrillic) {
            out += swap(query, latinToUk)
            out += swap(query, latinToRu)
        }
        return out.filter { it != query }
    }

    private fun swap(query: String, map: Map<Char, Char>): String =
        buildString(query.length) { query.forEach { append(map[it] ?: it) } }
}
