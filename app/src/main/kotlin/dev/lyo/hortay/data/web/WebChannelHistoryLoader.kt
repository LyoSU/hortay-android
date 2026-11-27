package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.suspendRunCatching
import dev.lyo.hortay.data.web.db.WebDatabase
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The web.db side of [WebChannelHistoryLoader], kept beside it rather than in
 * [WebRepository] (reached as [WebRepository.olderWalk]).
 */
class WebOlderWalkStore internal constructor(
    private val db: WebDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** What the walk needs to know about [username], read in one transaction. */
    suspend fun state(username: String): OlderWalkState = withContext(ioDispatcher) {
        db.transactionWithResult {
            OlderWalkState(
                storedPosts = db.postQueries.countByChannel(username).executeAsOne(),
                cursorSeq = db.postQueries.selectOlderWalkCursor(username).executeAsOneOrNull()?.cursor_seq,
                historyStartSeq = db.channelQueries.selectHistoryStart(username).executeAsOneOrNull()?.history_start_seq,
            )
        }
    }

    /** The channel has no post below [seq]. */
    suspend fun markHistoryStart(username: String, seq: Long) = withContext(ioDispatcher) {
        db.channelQueries.markHistoryStart(seq = seq, username = username)
    }
}

/**
 * [WebOlderWalkStore.state]: [storedPosts] of the channel (bookmarks included), the seq
 * the next `?before=` asks for ([cursorSeq] — the oldest post not kept only as a bookmark,
 * see `selectOlderWalkCursor`; null with no posts), and the persisted start of history
 * ([historyStartSeq], null until reached).
 */
data class OlderWalkState(val storedPosts: Long, val cursorSeq: Long?, val historyStartSeq: Long?)

/** Outcome of one user-driven older page of a guest channel ([WebChannelHistoryLoader]). */
sealed interface OlderPageResult {
    /** [count] posts older than what was stored were ingested (0 = nothing showable). */
    data class Loaded(val count: Int) : OlderPageResult

    /** Nothing older exists, or the channel screen's window is full; don't ask again. */
    data object End : OlderPageResult

    /** The shared t.me rate-limit backoff holds or t.me answered 429; nothing was waited on. */
    data object RateLimited : OlderPageResult

    /** Network error, or an answer that carried no page. */
    data object Failed : OlderPageResult
}

/**
 * Pages a guest channel's history backwards as the reader scrolls to its older edge —
 * the guest counterpart of the signed-in `PostsRepository.loadOlder`.
 *
 * A sweep only ever reads a channel's newest t.me/s/ page (plus `?after=` walks and the
 * bounded gap back-fill), so before this a guest channel screen ended at whatever the
 * sweeps had happened to store — about twenty posts for a quiet channel — and scrolling
 * past it loaded nothing.
 *
 * One call fetches `?before=<cursor>`, where the cursor is the oldest stored post that
 * isn't kept only as a bookmark ([WebOlderWalkStore.state]), and ingests the page
 * through [WebRepository.ingestOlderPosts] — the same path the gap back-fill uses, so the
 * ingest fingerprint guard, the channel-mismatch drop and the untouched channel row
 * (its validators and `older_cursor` describe the NEWEST page) all hold. Older posts
 * loaded this way are ordinary rows: the daily retention sweep trims them back to the
 * newest [WebRepository.DEFAULT_KEEP_PER_CHANNEL] like any other, and the walk simply
 * resumes from the oldest post left.
 *
 * End of history: a page with nothing listed below the cursor means the channel starts
 * there. The cursor is persisted as `channel.history_start_seq`, and while the oldest
 * stored post sits at or below it the call answers [OlderPageResult.End] without a
 * request — the reader isn't charged a round trip on every open of a short channel.
 * Retention dropping the oldest posts lifts the cursor above the mark again, so paging
 * resumes there. A channel whose stored count reaches the screen's query window
 * ([WebRepository.DEFAULT_FEED_LIMIT]) also answers End (not persisted): more posts
 * would land below what the screen can show.
 *
 * Rules shared with the other fetch paths:
 *  - one request per channel at a time — concurrent calls join the one in flight;
 *  - the channel's lock ([lockFor]) is held from the request through the ingest commit,
 *    so a sweep of the same channel can't interleave an older response;
 *  - the shared rate-limit gate is never waited on: while it holds, the call returns
 *    [OlderPageResult.RateLimited] at once and the screen offers Retry;
 *  - every fetched page goes to [onPage] (the view reporter's token cache);
 *  - the request runs on [scope], not the caller's: a reader who leaves the screen
 *    mid-request cancels only their wait, and the page still lands.
 *
 * A page whose older rows are all ones Hortay can't show (service rows, unparseable
 * posts) leaves the cursor where it was; the walk steps past it to the page below, up
 * to [SKIP_MAX_PAGES] pages per call.
 */
internal class WebChannelHistoryLoader(
    private val repository: WebRepository,
    private val scope: CoroutineScope,
    private val isGated: () -> Boolean,
    private val lockFor: (username: String) -> Mutex,
    private val fetchBefore: suspend (username: String, beforeSeq: Long) -> FetchResult,
    private val onPage: (WebChannelPage) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val windowLimit: Long = WebRepository.DEFAULT_FEED_LIMIT,
) {
    /** Per-channel request in flight; guarded by synchronized(itself). */
    private val inFlight = HashMap<String, Deferred<OlderPageResult>>()

    suspend fun loadOlder(username: String): OlderPageResult {
        val key = username.lowercase()
        val load = synchronized(inFlight) {
            inFlight[key]?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
                suspendRunCatching { lockFor(key).withLock { loadLocked(key) } }
                    .warnUnlessCancelled(TAG, "older page of $key")
                    .getOrElse { OlderPageResult.Failed }
            }.also { created ->
                inFlight[key] = created
                created.invokeOnCompletion { synchronized(inFlight) { inFlight.remove(key, created) } }
            }
        }
        load.start()
        return load.await()
    }

    private suspend fun loadLocked(username: String): OlderPageResult {
        val walk = repository.olderWalk.state(username)
        if (walk.storedPosts >= windowLimit) return OlderPageResult.End
        var before = walk.cursorSeq ?: return OlderPageResult.End
        if (walk.historyStartSeq != null && before <= walk.historyStartSeq) return OlderPageResult.End
        repeat(SKIP_MAX_PAGES) {
            if (isGated()) return OlderPageResult.RateLimited
            val page = when (val result = fetchBefore(username, before)) {
                is FetchResult.Page -> result.page
                is FetchResult.RateLimited -> return OlderPageResult.RateLimited
                else -> return OlderPageResult.Failed
            }
            onPage(page)
            val listedBelow = (page.listedSeqs.asSequence() + page.posts.asSequence().map { it.seq })
                .filter { it < before }
                .minOrNull()
            if (listedBelow == null) {
                repository.olderWalk.markHistoryStart(username, before)
                return OlderPageResult.End
            }
            val older = page.posts.count { it.seq < before && it.id.substringBefore('/').equals(username, ignoreCase = true) }
            if (older > 0) {
                repository.ingestOlderPosts(username, page.posts, nowMs())
                return OlderPageResult.Loaded(older)
            }
            before = listedBelow
        }
        return OlderPageResult.Loaded(0)
    }

    private companion object {
        const val TAG = "WebChannelHistory"

        /** Pages of rows Hortay can't show that one call steps past before giving up. */
        const val SKIP_MAX_PAGES = 3
    }
}
