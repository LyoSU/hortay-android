package dev.lyo.hortay.data.web

import android.util.Log
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.lyo.hortay.data.web.db.ChannelQueries
import dev.lyo.hortay.data.web.db.CustomEmojiQueries
import dev.lyo.hortay.data.web.db.PostQueries
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

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

    // ---- Reads (Flow) -------------------------------------------------------

    /**
     * Merged feed across every subscribed channel. The underlying SQL JOIN with
     * `channel.is_subscribed = 1` means unsubscribing a channel removes its
     * posts from the feed *immediately* on the next emit, with no manual filter.
     */
    fun observeFeed(limit: Long = DEFAULT_FEED_LIMIT): Flow<PersistentList<WebFeedEntry>> =
        postQueries
            .selectFeed(limit, mapper = ::feedRowMapper)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    fun observeFeedByChannel(
        username: String,
        limit: Long = DEFAULT_FEED_LIMIT,
    ): Flow<PersistentList<WebFeedEntry>> =
        postQueries
            .selectFeedByChannel(username, limit, mapper = ::feedRowMapper)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    fun observeBookmarked(): Flow<PersistentList<WebFeedEntry>> =
        postQueries
            .selectBookmarked(mapper = ::feedRowMapper)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    fun observePost(id: String): Flow<WebFeedEntry?> =
        postQueries
            .selectById(id, mapper = ::feedRowMapper)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    /**
     * Cross-channel substring search over post plain-text. Uses indexed LIKE on
     * the `text_plain` column — FTS5 isn't available on most Android SQLite
     * builds (Samsung / Pixel both ship without the module compiled in). For
     * realistic web-mode workloads (~5K posts × 2KB body) this still completes
     * in under 100 ms on low-end devices. The user's query is auto-wrapped in
     * `%…%` so callers pass the bare term; SQL wildcards in the input are
     * escaped to literal characters so a user typing "100%" doesn't accidentally
     * trigger a tautology.
     */
    fun search(query: String, limit: Long = DEFAULT_SEARCH_LIMIT): Flow<PersistentList<WebFeedEntry>> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "%${escaped}%"
        return postQueries
            .searchPlain(pattern, limit, mapper = ::feedRowMapper)
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

    fun observeSubscribedChannels(): Flow<PersistentList<ChannelEntry>> =
        channelQueries
            .selectAllSubscribed(mapper = ::channelEntryMapper)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { it.toPersistentList() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    fun observeChannel(username: String): Flow<ChannelEntry?> =
        channelQueries
            .selectByUsername(username, mapper = ::channelEntryMapper)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
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
                ingestPost(post, fetchedAtMs)
            }
        }
    }

    private fun ingestPost(post: WebPost, fetchedAtMs: Long) {
        val mediaJson = json.encodeToString(mediaListSerializer, post.media.toList())
        val previewJson = post.webPreview?.let { json.encodeToString(WebPreview.serializer(), it) }
        val forwardedJson = post.forwardedFrom?.let { json.encodeToString(WebForwardSource.serializer(), it) }
        val reactionsJson = json.encodeToString(reactionListSerializer, post.reactions.toList())
        val plainText = WebTextRenderer.toPlainText(post.textHtml)
        val publishedMs = parseIsoToMillis(post.publishedAt)
        val channelUsername = post.id.substringBefore('/')

        postQueries.upsertInsert(
            channelUsername = channelUsername,
            seq = post.seq,
            id = post.id,
            publishedAtIso = post.publishedAt,
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
            publishedAtIso = post.publishedAt,
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

    suspend fun markRead(postId: String) = withContext(ioDispatcher) {
        postQueries.markRead(postId)
    }

    /**
     * Called by Coil's image-loader error path when a CDN URL returns 401/403/410 —
     * the post's signed media URLs have expired and the channel needs a re-fetch.
     */
    suspend fun markMediaStale(postId: String) = withContext(ioDispatcher) {
        postQueries.markMediaStale(postId)
    }

    /**
     * Vacuum old non-bookmarked posts to bound DB growth. Driven by Phase 1.5's
     * background scheduler; cheap enough to call after every sweep too.
     */
    suspend fun vacuum(keepPerChannel: Long = DEFAULT_KEEP_PER_CHANNEL) = withContext(ioDispatcher) {
        postQueries.deleteOldPerChannel(keepPerChannel)
    }

    /**
     * Hard wipe — used by the "Очистити кеш" button. Subscriptions deliberately
     * survive: the user expressed intent, deleting their list would be hostile.
     * What gets cleared: cached channel metadata payloads, all posts (including
     * bookmarked — opt-in destruction), custom-emoji resolution cache.
     */
    suspend fun clearAllCache() = withContext(ioDispatcher) {
        db.transaction {
            postQueries.deleteAll()
            customEmojiQueries.deleteAll()
            // Channel rows survive but with nullified payload columns — the next
            // sweep will re-fetch and the user keeps their subscription list.
        }
    }

    // ---- Custom emoji cache --------------------------------------------------

    suspend fun cachedEmoji(emojiId: String, freshAfterMs: Long): CachedEmoji? =
        withContext(ioDispatcher) {
            customEmojiQueries.selectOne(emojiId, freshAfterMs).executeAsOneOrNull()?.let {
                CachedEmoji(it.emoji_id, it.type, it.url, it.thumb_url, it.size_px?.toInt())
            }
        }

    suspend fun cacheEmoji(
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

    private fun feedRowMapper(
        channel_username: String,
        seq: Long,
        id: String,
        published_at_iso: String,
        published_at_ms: Long,
        text_html: String,
        text_plain: String,
        media_json: String,
        web_preview_json: String?,
        forwarded_from_json: String?,
        reactions_json: String,
        views: String?,
        is_read: Boolean,
        is_bookmarked: Boolean,
        fetched_at_ms: Long,
        channel_title: String,
        channel_avatar: String?,
    ): WebFeedEntry {
        @Suppress("UNUSED_VARIABLE") val unusedTextPlain = text_plain // FTS-only column
        @Suppress("UNUSED_VARIABLE") val unusedFetchedAt = fetched_at_ms
        @Suppress("UNUSED_VARIABLE") val unusedPublishedMs = published_at_ms
        return WebFeedEntry(
            post = WebPost(
                id = id,
                seq = seq,
                publishedAt = published_at_iso,
                textHtml = text_html,
                media = decodeMedia(media_json),
                webPreview = web_preview_json?.let { decodePreview(it) },
                forwardedFrom = forwarded_from_json?.let { decodeForward(it) },
                views = views,
                reactions = decodeReactions(reactions_json),
            ),
            channel = WebChannelHeader(
                username = channel_username,
                title = channel_title,
                avatarUrl = channel_avatar,
            ),
            isRead = is_read,
            isBookmarked = is_bookmarked,
        )
    }

    private fun channelEntryMapper(
        username: String,
        title: String,
        description: String,
        avatar_url: String?,
        subscribers: String?,
        is_verified: Boolean,
        is_subscribed: Boolean,
        last_fetched_at_ms: Long?,
        last_etag: String?,
        last_modified: String?,
        older_cursor: String?,
        fetch_status: String,
        fetch_error: String?,
        fetch_retry_after_ms: Long?,
    ): ChannelEntry {
        @Suppress("UNUSED_VARIABLE") val unusedEtag = last_etag
        @Suppress("UNUSED_VARIABLE") val unusedModified = last_modified
        @Suppress("UNUSED_VARIABLE") val unusedCursor = older_cursor
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

    private fun parseIsoToMillis(iso: String): Long = runCatching {
        // ISO-8601 with offset, e.g. "2026-04-12T18:43:00+00:00". java.time can do
        // this on API 26+ (we already require minSdk 26 for the rest of the app).
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrElse { 0L }

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
