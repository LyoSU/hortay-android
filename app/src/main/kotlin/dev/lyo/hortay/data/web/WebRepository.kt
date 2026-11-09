package dev.lyo.hortay.data.web

import android.util.Log
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.escapeSqlLike
import dev.lyo.hortay.data.web.db.ChannelQueries
import dev.lyo.hortay.data.web.db.ChannelReadCursorQueries
import dev.lyo.hortay.data.web.db.CustomEmojiQueries
import dev.lyo.hortay.data.web.db.PostQueries
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Single data-access surface for the anonymous web-mode database. Every read goes
 * through here as a `Flow` driven by SQLDelight's [asFlow] — queries automatically
 * re-emit when any of their source tables change, so callers (StateFlow holders,
 * Compose `collectAsStateWithLifecycle`) never need to manually invalidate.
 *
 * Threading: SQLDelight queries themselves are synchronous; the [asFlow] adapter
 * dispatches collection on whatever flow context the caller chose. We pin reads
 * to [Dispatchers.IO] via [flowOn] so a long-running Composable collector never
 * blocks the main thread on a cursor materialise. Writes are explicit `suspend`
 * functions that switch to IO inside.
 *
 * Transactionality: every multi-statement write (e.g. ingesting a fetched
 * [WebChannelPage] = 1 channel upsert + 20 post upserts + per-post insert/update
 * pairs) goes through [WebDatabase.transaction] so it's atomic and observers
 * fire exactly once per logical change rather than per individual mutation.
 */
class WebRepository(
    private val db: WebDatabase,
    private val strings: StringResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WebEmojiCache {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val mediaListSerializer = ListSerializer(WebMedia.serializer())
    private val reactionListSerializer = ListSerializer(WebReaction.serializer())

    private val channelQueries: ChannelQueries get() = db.channelQueries
    private val postQueries: PostQueries get() = db.postQueries
    private val customEmojiQueries: CustomEmojiQueries get() = db.customEmojiQueries
    private val suggestionQueries get() = db.channelSuggestionQueries
    private val readCursorQueries: ChannelReadCursorQueries get() = db.channelReadCursorQueries

    // ---- Reads (Flow) -------------------------------------------------------

    /**
     * Merged feed across every subscribed channel. The underlying SQL JOIN with
     * `channel.is_subscribed = 1` means unsubscribing a channel removes its
     * posts from the feed *immediately* on the next emit, with no manual filter.
     */
    @OptIn(FlowPreview::class)
    fun observeFeed(limit: Long = DEFAULT_FEED_LIMIT): Flow<PersistentList<TimelinePost>> =
        postQueries
            .selectFeed(limit, mapper = ::rowToTimelinePost)
            .asFlow()
            // sample, not debounce: under a 200-channel burst sweep, individual
            // post inserts can land closer than the debounce window, so debounce
            // keeps resetting and never emits — the feed appears frozen until
            // the entire sweep completes (3-5 s of nothing). sample(150ms)
            // guarantees up to ~7 emits/s under continuous burst, so the UI
            // sees the feed grow incrementally as channels land. Bursts shorter
            // than 150 ms still coalesce, which is the original goal.
            .sample(FEED_REEMIT_SAMPLE_MS)
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    @OptIn(FlowPreview::class)
    fun observeFeedByChannel(
        username: String,
        limit: Long = DEFAULT_FEED_LIMIT,
    ): Flow<PersistentList<TimelinePost>> =
        postQueries
            .selectFeedByChannel(username, limit, mapper = ::rowToTimelinePost)
            .asFlow()
            .sample(FEED_REEMIT_SAMPLE_MS)
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    /**
     * Cross-channel substring search over post plain-text. Uses LIKE on the
     * `text_plain` column — FTS5 isn't available on most Android SQLite
     * builds (Samsung / Pixel both ship without the module compiled in). For
     * realistic web-mode workloads (~5K posts × 2KB body) this still completes
     * in under 100 ms on low-end devices. The user's query is auto-wrapped in
     * `%…%` so callers pass the bare term; SQL wildcards in the input are
     * escaped to literal characters so a user typing "100%" doesn't accidentally
     * trigger a tautology.
     *
     * Case-insensitivity: lowercase the query here (Kotlin's `lowercase()` is
     * Unicode-aware) and match it against `text_plain`, which ingest already
     * stores lowercased the same way. Android's bundled SQLite (no ICU) folds
     * ASCII case only, so `LOWER(text_plain)` alone never matched "Привіт"
     * against "привіт"; it is kept in the SQL only for rows that predate the
     * ingest-side fold ([vacuum] rewrites those). Index loss is a
     * non-issue because the existing leading `%` already defeated
     * `post_text_plain_idx` lookups; the LIKE has always been a sequential
     * scan, just one with case-folding now.
     */
    fun search(query: String, limit: Long = DEFAULT_SEARCH_LIMIT): Flow<PersistentList<TimelinePost>> {
        val pattern = "%${escapeSqlLike(query.lowercase())}%"
        return postQueries
            .searchPlain(pattern, limit, mapper = ::rowToTimelinePost)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .flowOn(ioDispatcher)
    }

    /** All known channels, including soft-deleted (`is_subscribed = 0`). */
    fun observeAllChannels(): Flow<PersistentList<ChannelEntry>> =
        channelQueries
            .selectAll(mapper = ::channelEntryMapper)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    /**
     * Snapshot list (no Flow) of subscribed channel usernames. Used by
     * [WebFeedSource] sweep-orchestration where Flow semantics aren't needed —
     * we want one authoritative list at sweep-start time, not a continually
     * updating one that could mutate mid-fan-out.
     */
    suspend fun subscribedUsernames(): List<String> = withContext(ioDispatcher) {
        channelQueries.selectAllSubscribed().executeAsList().map { it.username }
    }

    suspend fun channelsWithStaleMedia(olderThanMs: Long): List<String> = withContext(ioDispatcher) {
        postQueries.selectChannelsWithStaleMedia(olderThanMs).executeAsList()
    }

    /**
     * Map an expired media / preview CDN URL back to the id of the post that owns
     * it. Called from the Coil image-error path via [WebFeedSource.refetchForStaleMediaUrl]
     * to identify which channel needs a re-fetch. Matches the URL as a LIKE
     * substring of the stored JSON blobs (see [PostQueries.selectIdByMediaUrl]).
     * Returns null when no post carries the URL — e.g. a custom-emoji or already-
     * evicted URL — in which case the caller does nothing.
     */
    suspend fun postIdForMediaUrl(url: String): String? = withContext(ioDispatcher) {
        if (url.isBlank()) return@withContext null
        postQueries.selectIdByMediaUrl("%${escapeSqlLike(url)}%").executeAsOneOrNull()
    }

    /**
     * Snapshot of all stored [WebPost]s for a single channel. Used exclusively
     * by [WebFeedSource]'s diff-on-refresh archive path to obtain the previous
     * content before [ingestPage] overwrites it. Returns a map keyed by post id
     * (`"<username>/<seq>"`) for O(1) lookup against the freshly-parsed list.
     *
     * Only the fields that [WebPostDiff] inspects are reconstructed — views and
     * reactions are intentionally omitted so the diff ignores live-state churn.
     */
    suspend fun readWebPostsForChannel(username: String): Map<String, WebPost> =
        withContext(ioDispatcher) {
            postQueries.selectRawByChannel(username).executeAsList().associate { row ->
                row.id to WebPost(
                    id = row.id,
                    seq = row.seq,
                    publishedAt = row.published_at_iso,
                    textHtml = row.text_html,
                    media = decodeMedia(row.media_json),
                    webPreview = row.web_preview_json?.let { decodePreview(it) },
                    forwardedFrom = row.forwarded_from_json?.let { decodeForward(it) },
                    views = null,
                    reactions = kotlinx.collections.immutable.persistentListOf(),
                )
            }
        }

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
     * forwards, t.me/+invite links, group jumps) is silently skipped.
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
                if (u.length in MENTION_MIN_LEN..MENTION_MAX_LEN) {
                    freq.merge(u, 1, Int::plus)
                }
            }
            // Forwarded-from JSON: `{"channelName":..., "channelLink":"https://t.me/<u>"}`.
            // Cheaper to substring-extract than to spin up the JSON decoder
            // for what is one well-known shape.
            row.forwarded_from_json?.let { json ->
                FORWARD_LINK_REGEX.find(json)?.let { match ->
                    val u = match.groupValues[1].lowercase()
                    if (u.length in MENTION_MIN_LEN..MENTION_MAX_LEN) {
                        freq.merge(u, 1, Int::plus)
                    }
                }
            }
        }
        freq.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    // ---- Writes (suspend) ---------------------------------------------------

    /**
     * Atomic ingestion of one fetch result. Single transaction: channel upsert
     * + N post upserts. Triggers exactly one round of observer notifications
     * downstream — the merged feed Flow re-emits *once* even if 20 new posts
     * appeared.
     */
    suspend fun ingestPage(
        page: WebChannelPage,
        etag: String?,
        lastModified: String?,
        fetchedAtMs: Long,
        forceMediaRefresh: Boolean = false,
    ) = withContext(ioDispatcher) {
        db.transaction {
            val ch = page.channel
            channelQueries.upsertFromFetchInsert(
                username = ch.username,
                title = ch.title,
                description = ch.description,
                avatarUrl = ch.avatarUrl,
                subscribers = ch.subscribers,
                isVerified = ch.isVerified,
                lastFetchedAtMs = fetchedAtMs,
                lastEtag = etag,
                lastModified = lastModified,
                olderCursor = page.olderCursor,
            )
            channelQueries.upsertFromFetchUpdate(
                username = ch.username,
                title = ch.title,
                description = ch.description,
                avatarUrl = ch.avatarUrl,
                subscribers = ch.subscribers,
                isVerified = ch.isVerified,
                lastFetchedAtMs = fetchedAtMs,
                lastEtag = etag,
                lastModified = lastModified,
                olderCursor = page.olderCursor,
            )
            for (post in page.posts) {
                ingestPost(post, fetchedAtMs, forceMediaRefresh)
            }
        }
    }

    /**
     * Media-refresh ingest for a `?before=` page fetched by the stale-media path.
     * Only rows that already exist are rewritten (with the fingerprint guard
     * bypassed so the rotated CDN URLs land): the refetch exists to repair an
     * expired post, not to back-fill older history into the feed. The channel row
     * is deliberately left alone — its validators and `older_cursor` describe the
     * channel's NEWEST page, and the stale-media path relies on that cursor.
     */
    suspend fun refreshExistingPosts(posts: List<WebPost>, fetchedAtMs: Long) = withContext(ioDispatcher) {
        db.transaction {
            for (post in posts) {
                ingestPost(post, fetchedAtMs, forceMediaRefresh = true, onlyExisting = true)
            }
        }
    }

    private fun ingestPost(
        post: WebPost,
        fetchedAtMs: Long,
        forceMediaRefresh: Boolean,
        onlyExisting: Boolean = false,
    ) {
        val channelUsername = post.id.substringBefore('/')
        // Fingerprint guard: most posts are unchanged between sweeps. Skip the
        // four JSON serialises + UPDATE when text_html + views match the
        // existing row exactly. text_html captures edits (Telegram rewrites the
        // whole HTML on any text change including formatting / inline emoji);
        // views captures the much-faster-moving counter that we still want to
        // refresh. Other fields (media, preview, forward, reactions) only
        // change in lockstep with text_html in practice — a post whose text
        // didn't change rarely has new media, and reaction-count drift is
        // surfaced via the views/reactions tail of the same UPDATE we're
        // skipping anyway. media_url-rotation (the 1–4 h CDN TTL refresh) has two
        // escape hatches from this guard: a forced fetch ([forceMediaRefresh] —
        // pull-to-refresh or a media-TTL sweep that re-fetched specifically to pick
        // up rotated URLs) bypasses it unconditionally, and markMediaStale →
        // fetched_at_ms = 0 forces the upsert on the next sweep.
        val existing = postQueries.selectFingerprint(channelUsername, post.seq).executeAsOneOrNull()
        if (onlyExisting && existing == null) return
        // Fingerprint escape hatches. Without them a plateaued post (text + views
        // unchanged between sweeps) would keep its stale media JSON forever, so the
        // next Coil load would 401/403 again and re-trigger markMediaStale — a tight
        // loop with no visible recovery.
        //   • forceMediaRefresh: the fetch was forced precisely to refresh media URLs.
        //   • fetched_at_ms == 0: markMediaStale flagged this post's media as expired.
        if (!forceMediaRefresh &&
            existing != null &&
            existing.text_html == post.textHtml &&
            existing.views == post.views &&
            existing.fetched_at_ms != 0L
        ) {
            return
        }
        val mediaJson = json.encodeToString(mediaListSerializer, post.media.toList())
        val previewJson = post.webPreview?.let { json.encodeToString(WebPreview.serializer(), it) }
        val forwardedJson = post.forwardedFrom?.let { json.encodeToString(WebForwardSource.serializer(), it) }
        val reactionsJson = json.encodeToString(reactionListSerializer, post.reactions.toList())
        // Lowercased here (Unicode-aware) because SQLite's LOWER() only folds ASCII —
        // text_plain is a search-only column, see searchPlain in Post.sq.
        val plainText = WebTextRenderer.toPlainText(post.textHtml).lowercase()
        // Timestamp resolution when the page's `time[datetime]` is missing or
        // malformed (a selector regression yields an empty string for EVERY post):
        //   • the row already exists → keep its stored timestamp. Overwriting it
        //     with the fetch-time fallback re-dated every already-known post to
        //     "now" on each re-ingest, reshuffling the whole feed.
        //   • a new row → fall back to fetchedAtMs (the wallclock at the moment
        //     the page was received) instead of 0L, so the post isn't pinned at
        //     epoch 1970 as a phantom "ancient post". The ISO string is synthesised
        //     from the same instant because the UI derives the card date from it.
        val parsedMs = parseIsoToMillis(post.publishedAt)
        val publishedIso: String
        val publishedMs: Long
        when {
            parsedMs != null -> {
                publishedIso = post.publishedAt
                publishedMs = parsedMs
            }
            existing != null -> {
                publishedIso = existing.published_at_iso
                publishedMs = existing.published_at_ms
            }
            else -> {
                publishedIso = isoFromMillis(fetchedAtMs)
                publishedMs = fetchedAtMs
            }
        }

        postQueries.upsertInsert(
            channelUsername = channelUsername,
            seq = post.seq,
            id = post.id,
            publishedAtIso = publishedIso,
            publishedAtMs = publishedMs,
            textHtml = post.textHtml,
            textPlain = plainText,
            mediaJson = mediaJson,
            webPreviewJson = previewJson,
            forwardedFromJson = forwardedJson,
            reactionsJson = reactionsJson,
            views = post.views,
            fetchedAtMs = fetchedAtMs,
        )
        postQueries.upsertUpdate(
            channelUsername = channelUsername,
            seq = post.seq,
            publishedAtIso = publishedIso,
            publishedAtMs = publishedMs,
            textHtml = post.textHtml,
            textPlain = plainText,
            mediaJson = mediaJson,
            webPreviewJson = previewJson,
            forwardedFromJson = forwardedJson,
            reactionsJson = reactionsJson,
            views = post.views,
            fetchedAtMs = fetchedAtMs,
        )
    }

    suspend fun markFetchStatus(
        username: String,
        status: ChannelFetchStatus,
        error: String? = null,
        retryAfterMs: Long? = null,
        lastFetchedAtMs: Long? = null,
    ) = withContext(ioDispatcher) {
        channelQueries.markFetchStatus(
            username = username,
            status = status.dbValue,
            error = error,
            retryAfterMs = retryAfterMs,
            lastFetchedAtMs = lastFetchedAtMs,
        )
    }

    suspend fun markNotModified(username: String, fetchedAtMs: Long) = withContext(ioDispatcher) {
        channelQueries.markNotModified(username = username, lastFetchedAtMs = fetchedAtMs)
    }

    /**
     * Reset any [ChannelFetchStatus.Loading] rows back to [ChannelFetchStatus.Idle].
     * The 'loading' marker is set on entry to a fetch and cleared on exit; a
     * process kill mid-fetch leaves it stuck and the UI shows a permanent
     * spinner. Called once on [WebFeedSource] construction so the next sweep
     * picks the row up cleanly.
     */
    suspend fun clearStaleLoading() = withContext(ioDispatcher) {
        channelQueries.clearStaleLoading()
    }

    /** Demote one channel's `loading` status to `idle` — a cancelled fetch's cleanup. */
    suspend fun clearLoading(username: String) = withContext(ioDispatcher) {
        channelQueries.clearLoading(username)
    }

    /**
     * Subscribe-by-intent. Bootstraps a placeholder row if the user has never seen
     * the channel before; the next successful fetch replaces the placeholder.
     */
    suspend fun subscribe(username: String, placeholderTitle: String = username) =
        withContext(ioDispatcher) {
            db.transaction {
                channelQueries.subscribeInsert(username, placeholderTitle)
                channelQueries.subscribeUpdate(username)
            }
        }

    suspend fun unsubscribe(username: String) = withContext(ioDispatcher) {
        channelQueries.unsubscribe(username)
    }

    suspend fun setBookmark(postId: String, bookmarked: Boolean) = withContext(ioDispatcher) {
        postQueries.setBookmark(bookmarked, postId)
    }

    /**
     * Mirror the cross-mode [dev.lyo.hortay.data.BookmarkStore] key set
     * (`"<chatId>/<seq>"`, see [dev.lyo.hortay.data.bookmarkKey]) into
     * `post.is_bookmarked`. Guest chat ids are the synthetic
     * [WebPostAdapter.stableChatId] of the channel handle, so they are mapped
     * back through every known channel row; keys that don't resolve (TDLib-mode
     * bookmarks) are ignored. Idempotent full diff — doubles as the one-time
     * backfill for bookmarks made before the flag was written. Rows that don't
     * exist yet are simply not flagged (the UPDATE matches nothing).
     *
     * The flag is load-bearing: vacuum and "Clear cache" keep bookmarked rows,
     * and [observeBookmarked] feeds the Saved tab from it.
     */
    suspend fun syncBookmarks(keys: Set<String>) = withContext(ioDispatcher) {
        val usernameByChatId = channelQueries.selectAllUsernames().executeAsList()
            .associateBy { WebPostAdapter.stableChatId(it) }
        val target = keys.mapNotNullTo(HashSet()) { key ->
            val chatId = key.substringBefore('/').toLongOrNull()
            val seq = key.substringAfter('/').toLongOrNull()
            val username = chatId?.let { usernameByChatId[it] }
            if (username != null && seq != null) "$username/$seq" else null
        }
        db.transaction {
            val current = postQueries.selectBookmarkedIds().executeAsList().toHashSet()
            for (id in target) {
                if (id !in current) postQueries.setBookmark(true, id)
            }
            for (id in current) {
                if (id !in target) postQueries.setBookmark(false, id)
            }
        }
    }

    /** Every bookmarked post, regardless of subscription or feed window — the Saved tab source. */
    fun observeBookmarked(): Flow<PersistentList<TimelinePost>> =
        postQueries
            .selectBookmarked(mapper = ::rowToTimelinePost)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    /** True when [username] already has stored posts. */
    suspend fun hasPosts(username: String): Boolean = withContext(ioDispatcher) {
        postQueries.countByChannel(username).executeAsOne() > 0L
    }

    /** Smallest seq on the channel's newest fetched page, or null when unknown. */
    suspend fun newestPageFloorSeq(username: String): Long? = withContext(ioDispatcher) {
        channelQueries.selectOlderCursor(username).executeAsOneOrNull()?.older_cursor?.toLongOrNull()
    }

    suspend fun markRead(postId: String) = withContext(ioDispatcher) {
        postQueries.markRead(postId)
    }

    /**
     * Per-channel read-cursor advance — guest mode's parallel to TDLib's
     * [org.drinkless.tdlib.TdApi.ViewMessages] + `forceRead = true`. Atomic
     * INSERT OR IGNORE + UPDATE clamped to `MAX(...)` inside one transaction so
     * a stale viewport that emits a smaller seq after a real read cannot
     * rewind the cursor under us. Called from the viewport-dwell collector in
     * [dev.lyo.hortay.ui.timeline.TimelineScreen]; the cursor flow downstream
     * re-emits and the [dev.lyo.hortay.ui.timeline.UnreadStrip] for cards at
     * or below this seq fades out.
     */
    suspend fun markChannelRead(username: String, postSeq: Long) = withContext(ioDispatcher) {
        db.transaction {
            readCursorQueries.markReadInsert(username, postSeq)
            readCursorQueries.markReadUpdate(postSeq, username)
        }
    }

    /**
     * Snapshot of every channel's read cursor as a [ReadCursors] map, keyed by
     * the synthetic chat id [dev.lyo.hortay.data.web.WebPostAdapter.stableChatId]
     * uses for [TimelinePost.chatId]. The conversion happens inside the Flow
     * chain so the UI [dev.lyo.hortay.ui.timeline.LocalReadCursors] consumer
     * gets a drop-in replacement for the TDLib-mode cursor flow.
     */
    fun observeReadCursors(): kotlinx.coroutines.flow.Flow<ReadCursors> =
        readCursorQueries
            .selectAll()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows ->
                if (rows.isEmpty()) persistentMapOf()
                else rows.fold(persistentMapOf<Long, Long>()) { acc, row ->
                    acc.put(WebPostAdapter.stableChatId(row.username), row.last_seen_post_seq)
                }
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    /**
     * Zero a post's `fetched_at_ms` to flag its media URLs as expired. Reached from
     * the Coil image-error path: [dev.lyo.hortay.ui.media.TdMediaImage] reports the
     * failed URL through [dev.lyo.hortay.ui.media.LocalWebStaleMedia], which
     * [WebFeedSource.refetchForStaleMediaUrl] maps back to this post id via
     * [postIdForMediaUrl] before re-fetching the channel. Zeroing makes the
     * fingerprint guard in [ingestPost] let the fresh URLs through (the re-fetch
     * itself is driven by that caller; the proactive `selectChannelsWithStaleMedia`
     * sweep only looks at each channel's latest post).
     */
    suspend fun markMediaStale(postId: String) = withContext(ioDispatcher) {
        postQueries.markMediaStale(postId)
    }

    /**
     * Periodic maintenance, driven at most daily by [WebFeedSource] after a
     * successful sweep:
     *  - drops old non-bookmarked posts beyond [keepPerChannel] per channel to
     *    bound DB growth;
     *  - rewrites any `text_plain` not yet stored lowercased (rows ingested before
     *    ingest folded case — and unchanged since, so the fingerprint guard never
     *    re-wrote them), so non-ASCII search matches them too. Runs only while
     *    [foldSearchText] is true — the caller passes false once a pass has
     *    completed, so it is a one-shot migration rather than a per-launch scan.
     */
    suspend fun vacuum(
        keepPerChannel: Long = DEFAULT_KEEP_PER_CHANNEL,
        foldSearchText: Boolean = true,
    ) = withContext(ioDispatcher) {
        db.transaction {
            postQueries.deleteOldPerChannel(keepPerChannel)
            // One-shot legacy backfill: the caller ([WebFeedSource.maybeVacuum])
            // persists a done flag after the first successful pass, so steady-state
            // launches skip this full-table read entirely.
            if (foldSearchText) {
                for (row in postQueries.selectSearchText().executeAsList()) {
                    val folded = row.text_plain.lowercase()
                    if (folded != row.text_plain) postQueries.updateSearchText(folded, row.id)
                }
            }
        }
    }

    /**
     * Cache wipe used by the "Clear cache" Settings row. Subscriptions ALWAYS
     * survive — the user expressed intent by subscribing and deleting their list
     * would be hostile. Channel ROWS survive (so `is_subscribed = 1` stays
     * intact and the JOIN-based feed query still recognises subscriptions),
     * but their cached payload columns (title / avatar_url / subscribers /
     * fetch validators / cursor) are reset so the next sweep re-populates from
     * scratch instead of merging into potentially-stale data.
     *
     * [preserveBookmarks] decides whether bookmarked posts survive the wipe.
     * Default true: "clear cache" reads to a user as "remove transient stuff
     * I can re-fetch", not "delete things I deliberately saved". The opt-in
     * `false` path stays for callers that want a full reset (e.g. a future
     * "factory reset" affordance).
     *
     * Caller is expected to trigger a refresh after this returns — see
     * [WebFeedSource.clearCacheAndRefresh].
     */
    suspend fun clearAllCache(preserveBookmarks: Boolean = true) = withContext(ioDispatcher) {
        db.transaction {
            if (preserveBookmarks) postQueries.deleteAllExceptBookmarked()
            else postQueries.deleteAll()
            customEmojiQueries.deleteAll()
            channelQueries.resetCachedPayload()
        }
    }

    // ---- Custom emoji cache --------------------------------------------------

    override suspend fun cachedEmoji(emojiId: String, freshAfterMs: Long): CachedEmoji? =
        withContext(ioDispatcher) {
            customEmojiQueries.selectOne(emojiId, freshAfterMs).executeAsOneOrNull()?.let {
                CachedEmoji(it.emoji_id, it.type, it.url, it.thumb_url, it.size_px?.toInt())
            }
        }

    override suspend fun cacheEmoji(
        emojiId: String,
        type: String,
        url: String,
        thumbUrl: String?,
        sizePx: Int?,
        resolvedAtMs: Long,
    ) = withContext(ioDispatcher) {
        db.transaction {
            customEmojiQueries.upsertInsert(emojiId, type, url, thumbUrl, sizePx?.toLong(), resolvedAtMs)
            customEmojiQueries.upsertUpdate(type, url, thumbUrl, sizePx?.toLong(), resolvedAtMs, emojiId)
        }
    }

    // ---- Helpers (private) ---------------------------------------------------

    // SQLDelight emits row columns positionally to the mapper; we project only a
    // subset into [TimelinePost]. Unused params are prefixed with `_` so the
    // Kotlin compiler skips its UNUSED_PARAMETER check without needing a
    // @Suppress on every line. Intentionally dropped on the floor here:
    //   `_id` — composite PK; TimelinePost identifies posts by [seq]
    //   `_published_at_ms` — sort key, evaluated at the SQL layer
    //   `_text_plain` — search-index column, never displayed
    //   `_is_read` / `_is_bookmarked` — UI state lives in BookmarkStore / DataStore
    //     (is_bookmarked mirrors it for retention + the Saved query, see syncBookmarks)
    //   `_fetched_at_ms` — staleness gate, evaluated at the SQL layer
    private fun rowToTimelinePost(
        channel_username: String,
        seq: Long,
        _id: String,
        published_at_iso: String,
        _published_at_ms: Long,
        text_html: String,
        _text_plain: String,
        media_json: String,
        web_preview_json: String?,
        forwarded_from_json: String?,
        reactions_json: String,
        views: String?,
        _is_read: Boolean,
        _is_bookmarked: Boolean,
        _fetched_at_ms: Long,
        channel_title: String,
        channel_avatar: String?,
    ): TimelinePost {
        return WebPostAdapter.toTimelinePost(
            seq = seq,
            publishedAtIso = published_at_iso,
            textHtml = text_html,
            mediaList = decodeMedia(media_json),
            webPreview = web_preview_json?.let { decodePreview(it) },
            forwarded = forwarded_from_json?.let { decodeForward(it) },
            viewsRaw = views,
            reactionsList = decodeReactions(reactions_json),
            channelUsername = channel_username,
            channelTitle = channel_title,
            channelAvatarUrl = channel_avatar,
            strings = strings,
        )
    }

    // Same convention as [rowToTimelinePost]: `_`-prefixed params are columns the
    // SQL projection emits but [ChannelEntry] doesn't need. Dropped here:
    //   `_last_etag` / `_last_modified` — HTTP cache validators consumed by the
    //   fetcher, never surfaced to the UI.
    //   `_older_cursor` — pagination cursor, used by the fetcher.
    private fun channelEntryMapper(
        username: String,
        title: String,
        description: String,
        avatar_url: String?,
        subscribers: String?,
        is_verified: Boolean,
        is_subscribed: Boolean,
        last_fetched_at_ms: Long?,
        _last_etag: String?,
        _last_modified: String?,
        _older_cursor: String?,
        fetch_status: String,
        fetch_error: String?,
        fetch_retry_after_ms: Long?,
    ): ChannelEntry {
        return ChannelEntry(
            info = WebChannelInfo(
                username = username,
                title = title,
                description = description,
                avatarUrl = avatar_url,
                subscribers = subscribers,
                isVerified = is_verified,
            ),
            isSubscribed = is_subscribed,
            lastFetchedAtMs = last_fetched_at_ms,
            status = ChannelFetchStatus.fromDb(fetch_status),
            error = fetch_error,
            retryAfterMs = fetch_retry_after_ms,
        )
    }

    private fun decodeMedia(jsonStr: String): PersistentList<WebMedia> = runCatching {
        json.decodeFromString(mediaListSerializer, jsonStr).toPersistentList()
    }.getOrElse {
        Log.w(TAG, "media decode failed: ${it.message}")
        persistentListOf()
    }

    private fun decodeReactions(jsonStr: String): PersistentList<WebReaction> = runCatching {
        json.decodeFromString(reactionListSerializer, jsonStr).toPersistentList()
    }.getOrElse {
        Log.w(TAG, "reactions decode failed: ${it.message}")
        persistentListOf()
    }

    private fun decodePreview(jsonStr: String): WebPreview? = runCatching {
        json.decodeFromString(WebPreview.serializer(), jsonStr)
    }.getOrNull()

    private fun decodeForward(jsonStr: String): WebForwardSource? = runCatching {
        json.decodeFromString(WebForwardSource.serializer(), jsonStr)
    }.getOrNull()

    private fun isoFromMillis(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC).toString()

    private fun parseIsoToMillis(iso: String): Long? = runCatching {
        // ISO-8601 with offset, e.g. "2026-04-12T18:43:00+00:00". java.time can do
        // this on API 26+ (we already require minSdk 26 for the rest of the app).
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrNull()

    companion object {
        private const val TAG = "WebRepository"

        /**
         * Default LIMIT for [observeFeed]. 1000 is enough for realistic infinite
         * scroll on a 200-channel subscription set (~5 posts/channel/day average)
         * and small enough that the SELECT + JSON decode for the feed flow stays
         * under 50ms cold on a low-end device.
         */
        const val DEFAULT_FEED_LIMIT = 1000L

        const val DEFAULT_SEARCH_LIMIT = 200L

        // Coalesce burst writes during sweep (markFetchStatus → ingestPage → final
        // status, ×concurrency=6) into a single feed re-read. 50 ms is the same
        // window CustomEmojiRepository uses to batch GetCustomEmojiStickers calls.
        // sample window for the SQLDelight asFlow() pipeline. Picked to be longer
    // than a typical inter-write gap during a 200-channel burst sweep, so the
    // UI receives steady ~7Hz updates as content lands. Bursts shorter than
    // this window still coalesce. Earlier value was a 50ms debounce, but
    // debounce never settled under continuous burst → feed appeared frozen.
    private const val FEED_REEMIT_SAMPLE_MS = 150L

        /**
         * Per-channel post-retention cap for the vacuum task. Deliberately generous —
         * with PersistentList structural sharing on the feed and FTS5's external
         * content storage, the marginal disk cost of an extra 100 posts per channel
         * is negligible (~50 KB) and they remain searchable from local history.
         */
        const val DEFAULT_KEEP_PER_CHANNEL = 200L

        /**
         * Default sample size for [mentionedUsernamesFromSubscribed]. 200 posts
         * across all subscribed channels gives a representative "what gets
         * forwarded / talked about most" snapshot without paying the IO cost
         * of scanning every post (~5K possible).
         */
        const val MENTIONS_DEFAULT_LIMIT = 200L

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
    }
}

/**
 * Repository-level mirror of [WebChannelInfo] enriched with subscription / fetch
 * state. [WebChannelInfo] stays a pure parser model; this layer adds the bits
 * the UI needs to draw error chips and last-updated indicators.
 */
@androidx.compose.runtime.Immutable
data class ChannelEntry(
    val info: WebChannelInfo,
    val isSubscribed: Boolean,
    val lastFetchedAtMs: Long?,
    val status: ChannelFetchStatus,
    val error: String?,
    val retryAfterMs: Long?,
)

/**
 * Fetch-status enum mirrored to/from the `channel.fetch_status` text column.
 * Stored as text (not int) so a future `git log` of the schema reads naturally
 * and direct SQLite shell inspection isn't a numeric riddle.
 */
enum class ChannelFetchStatus(val dbValue: String) {
    Idle("idle"),
    Loading("loading"),
    Ok("ok"),
    NotFound("not_found"),
    Private("private"),
    RateLimited("rate_limited"),
    Error("error"),
    ParseFailure("parse_failure");

    companion object {
        fun fromDb(value: String): ChannelFetchStatus = entries.firstOrNull { it.dbValue == value } ?: Idle
    }
}

/** Decoded shape of [WebRepository.cachedEmoji]. */
@androidx.compose.runtime.Immutable
data class CachedEmoji(
    val emojiId: String,
    val type: String,
    val url: String,
    val thumbUrl: String?,
    val sizePx: Int?,
)
