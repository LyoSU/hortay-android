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
    private val readCursorQueries: ChannelReadCursorQueries get() = db.channelReadCursorQueries

    /** Reads and the start-of-history mark of the user-driven older-page walk. */
    val olderWalk = WebOlderWalkStore(db, ioDispatcher)

    /** Per-channel new-post notification state (bell + baseline). */
    val notify = WebNotifyStore(db, ioDispatcher)

    private val discovery = WebChannelDiscovery(db, ::decodeForward, ioDispatcher)

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
     * [username]'s posts above [afterSeq], newest first, mapped like the feed — the
     * candidates of a new-post notification ([WebNotifyCheck]). One snapshot, not a Flow.
     */
    suspend fun channelPostsAfter(
        username: String,
        afterSeq: Long,
        limit: Long = NOTIFY_CANDIDATE_LIMIT,
    ): List<TimelinePost> = withContext(ioDispatcher) {
        postQueries.selectChannelPostsAfter(username, afterSeq, limit, mapper = ::rowToTimelinePost)
            .executeAsList()
    }

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
            .selectAll()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map(::channelEntry).toPersistentList() }
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

    /** See [WebChannelDiscovery.mentionedUsernamesFromSubscribed]. */
    suspend fun mentionedUsernamesFromSubscribed(): List<Pair<String, Int>> =
        discovery.mentionedUsernamesFromSubscribed()

    /** See [WebChannelDiscovery.guestSearchCandidates]. */
    suspend fun guestSearchCandidates(): List<GuestChannelCandidate> = discovery.guestSearchCandidates()

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
                channelId = page.channelId,
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
                channelId = page.channelId,
            )
            for (post in page.posts) {
                ingestPost(post, ch.username, fetchedAtMs, forceMediaRefresh)
            }
            dropDeletedPosts(ch.username, page.listedSeqs)
        }
    }

    /**
     * Remove stored posts that the page's seq range no longer lists (deleted by the
     * channel). Needs at least two listed posts to have a range at all; SQLite's bound
     * variable limit is far above a page's ~20 ids.
     */
    private fun dropDeletedPosts(username: String, listedSeqs: List<Long>) {
        if (listedSeqs.size < 2) return
        postQueries.deleteMissingInRange(
            channelUsername = username,
            minSeq = listedSeqs.min(),
            maxSeq = listedSeqs.max(),
            listedSeqs = listedSeqs,
        )
    }

    /**
     * Ingest posts from a page that is not the channel's newest one: a `?before=` page
     * fetched to close a gap, or an `?after=` page of newer posts (see
     * `WebFeedSource.fillGap` / `fetchNewerOnly`). Posts are inserted like on a normal
     * sweep; the channel row is left alone — its validators and `older_cursor` describe
     * the NEWEST full page. [channelUsername] is the channel the page was fetched for;
     * posts naming any other channel are dropped (see [ingestPost]).
     */
    suspend fun ingestOlderPosts(
        channelUsername: String,
        posts: List<WebPost>,
        fetchedAtMs: Long,
    ) = withContext(ioDispatcher) {
        db.transaction {
            for (post in posts) {
                ingestPost(post, channelUsername, fetchedAtMs, forceMediaRefresh = false)
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
     * [channelUsername] is the channel the page was fetched for.
     */
    suspend fun refreshExistingPosts(
        channelUsername: String,
        posts: List<WebPost>,
        fetchedAtMs: Long,
    ) = withContext(ioDispatcher) {
        db.transaction {
            for (post in posts) {
                ingestPost(post, channelUsername, fetchedAtMs, forceMediaRefresh = true, onlyExisting = true)
            }
        }
    }

    /**
     * [expectedChannel] is the channel whose page carried [post]. The post's own key
     * comes from the page's raw `data-post` attribute (`<channel>/<seq>`), which
     * nothing else ties to the fetched channel — a page for one channel naming
     * another would otherwise write posts under that other subscription. A mismatch
     * (compared case-insensitively; handles are case-insensitive) drops the post.
     */
    private fun ingestPost(
        post: WebPost,
        expectedChannel: String,
        fetchedAtMs: Long,
        forceMediaRefresh: Boolean,
        onlyExisting: Boolean = false,
    ) {
        val channelUsername = post.id.substringBefore('/')
        if (!channelUsername.equals(expectedChannel, ignoreCase = true)) {
            Log.w(TAG, "ingest: dropping post ${post.id} from a page of $expectedChannel")
            return
        }
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
        //
        // The guard also compares the reactions, the forward header, and the SHAPE of
        // the media and the link preview (their CDN URLs rotate on every fetch, so the
        // raw JSON can't be compared). Text + views alone froze reaction counts on a
        // post whose view counter had plateaued, and missed a media-only edit.
        val parsedMs = parseIsoToMillis(post.publishedAt)
        val forwardedJson = post.forwardedFrom?.let { json.encodeToString(WebForwardSource.serializer(), it) }
        val reactionsJson = json.encodeToString(reactionListSerializer, post.reactions.toList())
        if (!forceMediaRefresh &&
            existing != null &&
            existing.text_html == post.textHtml &&
            existing.views == post.views &&
            existing.fetched_at_ms != 0L &&
            existing.reactions_json == reactionsJson &&
            existing.forwarded_from_json == forwardedJson &&
            sameExtras(existing.extras_json, post.extras) &&
            sameMediaShape(existing.media_json, post.media) &&
            samePreview(existing.web_preview_json, post.webPreview)
        ) {
            return
        }
        val mediaJson = json.encodeToString(mediaListSerializer, post.media.toList())
        val previewJson = post.webPreview?.let { json.encodeToString(WebPreview.serializer(), it) }
        val extrasJson = post.extras.takeUnless { it.isEmpty }
            ?.let { json.encodeToString(WebPostExtras.serializer(), it) }
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
            extrasJson = extrasJson,
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
            extrasJson = extrasJson,
        )
    }

    /** Stored media vs fresh media, ignoring the URLs (they rotate with the CDN token). */
    private fun sameMediaShape(storedJson: String, fresh: List<WebMedia>): Boolean {
        if (storedJson == "[]") return fresh.isEmpty()
        val stored = runCatching { json.decodeFromString(mediaListSerializer, storedJson) }
            .getOrNull() ?: return false
        if (stored.size != fresh.size) return false
        return stored.indices.all { i ->
            val a = stored[i]
            val b = fresh[i]
            a.kind == b.kind && a.durationSec == b.durationSec && a.title == b.title &&
                a.aspectRatio == b.aspectRatio && a.url.isBlank() == b.url.isBlank()
        }
    }

    /** Stored extras vs fresh ones, ignoring the reply thumbnail's (rotating) URL. */
    private fun sameExtras(storedJson: String?, fresh: WebPostExtras): Boolean {
        val stored = decodeExtras(storedJson)
        fun WebPostExtras.stable() = copy(reply = reply?.copy(thumbUrl = null))
        return stored.stable() == fresh.stable() &&
            (stored.reply?.thumbUrl == null) == (fresh.reply?.thumbUrl == null)
    }

    private fun decodeExtras(storedJson: String?): WebPostExtras =
        storedJson?.let { runCatching { json.decodeFromString(WebPostExtras.serializer(), it) }.getOrNull() }
            ?: WebPostExtras()

    /** Stored link preview vs fresh one, ignoring the (rotating) image URL. */
    private fun samePreview(storedJson: String?, fresh: WebPreview?): Boolean {
        if (storedJson == null) return fresh == null
        if (fresh == null) return false
        val stored = runCatching { json.decodeFromString(WebPreview.serializer(), storedJson) }
            .getOrNull() ?: return false
        return stored.copy(imageUrl = null) == fresh.copy(imageUrl = null) &&
            (stored.imageUrl == null) == (fresh.imageUrl == null)
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
        subscribeAll(mapOf(username to placeholderTitle))

    /**
     * [subscribe] for many channels (username → placeholder title) in ONE transaction, so
     * the channel-list import emits one table change instead of one per channel.
     */
    suspend fun subscribeAll(placeholderTitles: Map<String, String>) = withContext(ioDispatcher) {
        db.transaction {
            placeholderTitles.forEach { (username, title) ->
                channelQueries.subscribeInsert(username, title)
                channelQueries.subscribeUpdate(username)
            }
        }
    }

    suspend fun unsubscribe(username: String) = withContext(ioDispatcher) {
        channelQueries.unsubscribe(username)
    }

    /**
     * One-time, idempotent repair for data written before [TmePageParser] lowercased
     * handles. Those builds keyed a fetched channel by the page header's display casing,
     * so the table could hold a lowercase subscription row ("durov") next to a
     * mixed-case fetch row ("Durov") owning the posts ("Durov/123"). After the parser
     * fix every refetch lands under "durov/123" instead, so without this the old rows'
     * is_read / is_bookmarked were stranded, the bookmark mirror ([syncBookmarks], which
     * maps chat ids back through every username) could target a spelling that no
     * longer receives updates, and "Clear cache" could delete the only flagged copy's
     * live twin while keeping a dead one.
     *
     * For every mixed-case channel row, in ONE transaction:
     *  - the lowercase row is created if missing (carrying the mixed row's subscription
     *    flag); an existing lowercase row keeps its own flag — every subscribe /
     *    unsubscribe path writes the lowercase spelling, so it is the user's intent;
     *  - each post is re-keyed to the lowercase channel and id, or — when the lowercase
     *    twin already exists — its read / bookmark flags are OR-ed into the twin (whose
     *    content is the newer fetch) and the stale row dropped;
     *  - the read cursor is folded in with MAX;
     *  - the mixed-case row is deleted.
     * Plain INSERT OR IGNORE / UPDATE / DELETE only (Android 8/9 SQLite < 3.24). No
     * schema change, so no .sqm migration: the cheap `username != LOWER(username)` probe
     * makes repeat runs a no-op, which is why it is safe to call on every launch.
     *
     * @return the number of mixed-case channel rows merged.
     */
    suspend fun normalizeUsernameCase(): Int = withContext(ioDispatcher) {
        db.transactionWithResult {
            val mixed = channelQueries.selectMixedCaseUsernames().executeAsList()
            for (mixedName in mixed) {
                val lower = mixedName.lowercase()
                channelQueries.selectByUsername(mixedName).executeAsOneOrNull()?.let { row ->
                    channelQueries.insertNormalized(
                        username = lower,
                        title = row.title,
                        description = row.description,
                        avatarUrl = row.avatar_url,
                        subscribers = row.subscribers,
                        isVerified = row.is_verified,
                        isSubscribed = row.is_subscribed,
                        channelId = row.channel_id,
                    )
                }
                val lowerState = postQueries.selectUserStateByChannel(lower).executeAsList()
                    .associateBy { it.seq }
                for (post in postQueries.selectUserStateByChannel(mixedName).executeAsList()) {
                    val twin = lowerState[post.seq]
                    if (twin == null) {
                        postQueries.rekey(
                            newChannelUsername = lower,
                            newId = "$lower/${post.seq}",
                            channelUsername = mixedName,
                            seq = post.seq,
                        )
                    } else {
                        postQueries.setUserState(
                            isRead = twin.is_read || post.is_read,
                            isBookmarked = twin.is_bookmarked || post.is_bookmarked,
                            channelUsername = lower,
                            seq = post.seq,
                        )
                        postQueries.deleteOne(channelUsername = mixedName, seq = post.seq)
                    }
                }
                readCursorQueries.selectByUsername(mixedName).executeAsOneOrNull()?.let { cursor ->
                    readCursorQueries.markReadInsert(lower, cursor)
                    readCursorQueries.markReadUpdate(cursor, lower)
                    readCursorQueries.deleteByUsername(mixedName)
                }
                channelQueries.deleteChannel(mixedName)
            }
            mixed.size
        }
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

    /** Seq of the newest stored post of [username], or null when it has none. */
    suspend fun maxSeq(username: String): Long? = withContext(ioDispatcher) {
        postQueries.selectMaxSeq(username).executeAsOneOrNull()?.max_seq
    }

    /** An unfinished history back-fill: continue `?before=`[beforeSeq] down to [untilSeq]. */
    data class PendingGap(val beforeSeq: Long, val untilSeq: Long)

    suspend fun pendingGap(username: String): PendingGap? = withContext(ioDispatcher) {
        db.channelGapQueries.selectByUsername(username).executeAsOneOrNull()
            ?.let { PendingGap(beforeSeq = it.before_seq, untilSeq = it.until_seq) }
    }

    suspend fun saveGap(username: String, gap: PendingGap) = withContext(ioDispatcher) {
        db.channelGapQueries.save(username, gap.beforeSeq, gap.untilSeq)
    }

    suspend fun clearGap(username: String) = withContext(ioDispatcher) {
        db.channelGapQueries.deleteByUsername(username)
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
    /**
     * The real Telegram channel id behind [username] (`channel.channel_id`), or null when
     * no full fetch has seen one yet. Lets a signed-in run tell offline whether the
     * account already follows the channel (see `KnownChannelMembership`).
     */
    suspend fun channelIdFor(username: String): Long? = withContext(ioDispatcher) {
        channelQueries.selectChannelId(username).executeAsOneOrNull()?.channel_id
    }

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
                    acc.putting(WebPostAdapter.stableChatId(row.username), row.last_seen_post_seq)
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
            db.channelGapQueries.deleteAll()
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
    // (`_id` and `_fetched_at_ms` do feed the mapped-post memo below, nothing else.)
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
        extrasJson: String?,
        channel_title: String,
        channel_avatar: String?,
    ): TimelinePost {
        // Memo of the mapped post. The feed queries JOIN `channel`, so every write to
        // that table — two status writes per channel per sweep — re-runs them, and each
        // run used to decode four JSON columns and rebuild the formatted text for up to
        // a thousand rows that had not changed. Hashing the raw columns is far cheaper
        // than that, and handing back the SAME instance also lets the downstream
        // `distinctUntilChanged` and Compose compare by identity.
        val memoKey = PostMemoKey(
            fetchedAtMs = _fetched_at_ms,
            channelTitle = channel_title,
            channelAvatar = channel_avatar,
            contentHash = java.util.Objects.hash(
                published_at_iso, text_html, media_json, web_preview_json,
                forwarded_from_json, reactions_json, views, extrasJson,
            ),
            locale = java.util.Locale.getDefault(),
        )
        synchronized(postMemo) { postMemo[_id] }
            ?.takeIf { it.key == memoKey }
            ?.let { return it.post }
        val post = WebPostAdapter.toTimelinePost(
            seq = seq,
            publishedAtIso = published_at_iso,
            textHtml = text_html,
            mediaList = decodeMedia(media_json),
            webPreview = web_preview_json?.let { decodePreview(it) },
            forwarded = forwarded_from_json?.let { decodeForward(it) },
            viewsRaw = views,
            reactionsList = decodeReactions(reactions_json),
            extras = decodeExtras(extrasJson),
            channelUsername = channel_username,
            channelTitle = channel_title,
            channelAvatarUrl = channel_avatar,
            strings = strings,
        )
        synchronized(postMemo) { postMemo[_id] = PostMemo(memoKey, post) }
        return post
    }

    /**
     * Everything a mapped post depends on. [contentHash] folds the raw columns;
     * [fetchedAtMs] (rewritten by every post upsert) makes a hash collision between
     * two versions of one post practically impossible. [locale] because the mapper
     * bakes localized strings (media badges, the "not shown on the web" notice) in.
     */
    private data class PostMemoKey(
        val fetchedAtMs: Long,
        val channelTitle: String,
        val channelAvatar: String?,
        val contentHash: Int,
        val locale: java.util.Locale,
    )

    private class PostMemo(val key: PostMemoKey, val post: TimelinePost)

    /** Keyed by post id; sized above the feed window ([DEFAULT_FEED_LIMIT]) plus a channel screen. */
    private val postMemo = dev.lyo.hortay.ui.media.boundedLruCache<String, PostMemo>(POST_MEMO_SIZE, accessOrder = true)

    // The HTTP validators (`last_etag` / `last_modified`), the pagination cursor and
    // `channel_id` are fetcher-side state, never surfaced to the UI.
    private fun channelEntry(row: dev.lyo.hortay.data.web.db.Channel): ChannelEntry = ChannelEntry(
        info = WebChannelInfo(
            username = row.username,
            title = row.title,
            description = row.description,
            avatarUrl = row.avatar_url,
            subscribers = row.subscribers,
            isVerified = row.is_verified,
        ),
        isSubscribed = row.is_subscribed,
        lastFetchedAtMs = row.last_fetched_at_ms,
        status = ChannelFetchStatus.fromDb(row.fetch_status),
        error = row.fetch_error,
        retryAfterMs = row.fetch_retry_after_ms,
        notify = row.notify,
    )

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

        /** Mapped-post memo capacity: the feed window plus an open channel screen. */
        private const val POST_MEMO_SIZE = 2048

        const val DEFAULT_SEARCH_LIMIT = 200L

        /** More than a t.me/s/ page (~20 posts): a background check never ingests more than one. */
        const val NOTIFY_CANDIDATE_LIMIT = 50L

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
    /** The reader turned this channel's new-post notifications on (see [WebNotifyStore]). */
    val notify: Boolean = false,
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
