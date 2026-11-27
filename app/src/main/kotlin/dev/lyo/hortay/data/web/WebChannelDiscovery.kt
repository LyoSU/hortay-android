package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Channel-discovery reads over web.db: usernames that followed channels point at
 * (mentions and forward sources) and the raw candidate set for the guest
 * Add-channel sheet's offline name search. Read-only; [WebRepository] owns the
 * instance and delegates to it. [decodeForward] is the repository's
 * [WebForwardSource] decoder, so both read the forward JSON with one configuration.
 */
internal class WebChannelDiscovery(
    private val db: WebDatabase,
    private val decodeForward: (String) -> WebForwardSource?,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val channelQueries get() = db.channelQueries
    private val postQueries get() = db.postQueries
    private val suggestionQueries get() = db.channelSuggestionQueries

    /**
     * Channels the user might want to follow next: usernames mentioned via
     * `@handle` inside post bodies, or appearing as the source of a forward,
     * across the most recent [limitPosts] posts of every subscribed channel.
     * Returned in descending frequency so "the channels your channels keep
     * pointing at" surface first. Ranking is purely a count — we deliberately
     * don't bias by recency or by which subscribed source mentioned them, so
     * the suggestion stays explainable ("3 of your channels mention this").
     *
     * The username extraction is conservative: we only accept ASCII handles
     * matching Telegram's public-username rules ([A-Za-z][A-Za-z0-9_]{1,31}),
     * lowercased for stable comparison. Anything that doesn't match (private
     * forwards, t.me/+invite links, group jumps) is silently skipped, and so
     * are bot handles ([isBotUsername]) — never a channel to follow.
     */
    suspend fun mentionedUsernamesFromSubscribed(
        limitPosts: Long = MENTIONS_DEFAULT_LIMIT,
    ): List<Pair<String, Int>> = withContext(ioDispatcher) {
        val rows = postQueries.selectMentionsSource(limitPosts).executeAsList()
        if (rows.isEmpty()) return@withContext emptyList()
        val freq = HashMap<String, Int>()
        for (row in rows) {
            // @username mentions inside post text. Bodies are HTML, so the
            // regex needs to skip href attributes — we want the *visible*
            // mention, not the t.me/<u>/<seq> URL inside an <a>.
            VISIBLE_MENTION_REGEX.findAll(row.text_html).forEach { match ->
                val u = match.groupValues[1].lowercase()
                if (u.length in MENTION_MIN_LEN..MENTION_MAX_LEN && !isBotUsername(u)) {
                    freq.merge(u, 1, Int::plus)
                }
            }
            // Forwarded-from JSON: `{"channelName":..., "channelLink":"https://t.me/<u>"}`.
            // Cheaper to substring-extract than to spin up the JSON decoder
            // for what is one well-known shape.
            row.forwarded_from_json?.let { json ->
                FORWARD_LINK_REGEX.find(json)?.let { match ->
                    val u = match.groupValues[1].lowercase()
                    if (u.length in MENTION_MIN_LEN..MENTION_MAX_LEN && !isBotUsername(u)) {
                        freq.merge(u, 1, Int::plus)
                    }
                }
            }
        }
        freq.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    /**
     * Everything web.db knows about channels, as raw [GuestChannelCandidate]s for
     * [GuestChannelIndex.build] — the guest Add-channel sheet's offline name search.
     * Read once when the sheet opens (and again when the subscriptions change), never
     * per keystroke:
     *
     *  - every `channel` row, followed or not ([GuestChannelSource.Subscribed] /
     *    [GuestChannelSource.Known]), with its title, description, avatar and count;
     *  - `channel_suggestion` rows;
     *  - forward sources (name + username) and visible `@mentions` in the newest
     *    [postSample] posts of followed channels, counted per username; bot handles
     *    ([isBotUsername]) are left out.
     *
     * The curated catalog lives outside web.db; the caller adds it.
     */
    suspend fun guestSearchCandidates(
        postSample: Long = SEARCH_POSTS_SAMPLE,
    ): List<GuestChannelCandidate> = withContext(ioDispatcher) {
        val out = ArrayList<GuestChannelCandidate>()
        channelQueries.selectAll().executeAsList().forEach { row ->
            out += GuestChannelCandidate(
                username = row.username,
                title = row.title,
                description = row.description,
                avatarUrl = row.avatar_url,
                subscribers = row.subscribers,
                source = if (row.is_subscribed) GuestChannelSource.Subscribed else GuestChannelSource.Known,
            )
        }
        suggestionQueries.selectAll().executeAsList().forEach { row ->
            out += GuestChannelCandidate(
                username = row.username,
                title = row.title,
                description = row.description,
                avatarUrl = row.avatar_url,
                source = if (row.source == "history") GuestChannelSource.History else GuestChannelSource.Catalog,
            )
        }
        val forwardCount = HashMap<String, Int>()
        // Newest name per forward source: rows come newest first, so the first wins.
        val forwardName = HashMap<String, String>()
        val mentionCount = HashMap<String, Int>()
        postQueries.selectMentionsSource(postSample).executeAsList().forEach { row ->
            VISIBLE_MENTION_REGEX.findAll(row.text_html).forEach { match ->
                val u = match.groupValues[1].lowercase()
                if (u.length in MENTION_MIN_LEN..MENTION_MAX_LEN && !isBotUsername(u)) {
                    mentionCount.merge(u, 1, Int::plus)
                }
            }
            val forward = row.forwarded_from_json?.let { decodeForward(it) } ?: return@forEach
            val u = FORWARD_URL_REGEX.find(forward.channelLink)?.groupValues?.get(1)?.lowercase()
                ?: return@forEach
            if (u.length !in MENTION_MIN_LEN..MENTION_MAX_LEN || isBotUsername(u)) return@forEach
            forwardCount.merge(u, 1, Int::plus)
            if (forward.channelName.isNotBlank()) forwardName.putIfAbsent(u, forward.channelName)
        }
        forwardCount.forEach { (u, n) ->
            out += GuestChannelCandidate(
                username = u,
                title = forwardName[u],
                source = GuestChannelSource.Forwarded,
                count = n,
            )
        }
        mentionCount.forEach { (u, n) ->
            out += GuestChannelCandidate(username = u, source = GuestChannelSource.Mentioned, count = n)
        }
        out
    }

    companion object {
        /**
         * Default sample size for [mentionedUsernamesFromSubscribed]. 200 posts
         * across all subscribed channels gives a representative "what gets
         * forwarded / talked about most" snapshot without paying the IO cost
         * of scanning every post (~5K possible).
         */
        const val MENTIONS_DEFAULT_LIMIT = 200L

        /**
         * Post sample for [guestSearchCandidates]. Larger than [MENTIONS_DEFAULT_LIMIT]:
         * the search wants breadth (any channel the reader may have seen forwarded),
         * and it runs once per sheet open, not per keystroke.
         */
        const val SEARCH_POSTS_SAMPLE = 1000L

        // Telegram public-username constraints (matches parseUsernameFromInput).
        private const val MENTION_MIN_LEN = 2
        private const val MENTION_MAX_LEN = 32

        // Bare `@username` in visible text (HTML or plaintext). Negative
        // lookbehind prevents matching emails (`foo@bar`) or URL fragments
        // (`href="...?at=@user"`).
        private val VISIBLE_MENTION_REGEX =
            Regex("""(?<![A-Za-z0-9_./?=&])@([A-Za-z][A-Za-z0-9_]{1,31})\b""")

        // "channelLink":"https://t.me/<u>" / "tg://resolve?domain=<u>"
        private val FORWARD_LINK_REGEX =
            Regex(""""channelLink"\s*:\s*"(?:https?://)?t\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{1,31})""")

        // The same, on a decoded [WebForwardSource.channelLink].
        private val FORWARD_URL_REGEX =
            Regex("""^(?:https?://)?t\.me/(?:s/)?([A-Za-z][A-Za-z0-9_]{1,31})""")
    }
}
