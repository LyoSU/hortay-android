package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * Per-chat read-state cursors. Key = `chatId`, value = `lastReadInboxMessageId`
 * — the highest message id the local user has read in that chat (TDLib mode) or
 * the highest post id they've dwelt on (guest / web mode). A post is **unread**
 * when its `id` is strictly greater than the cursor for its chat.
 *
 * This typealias is the **snapshot** form: data-layer flows
 * ([PostsRepository.chatReadCursors], [WebFeedSource.chatReadCursors]) emit
 * a fresh PersistentMap on each cursor advance, and snapshot-style consumers
 * (TimelineUiState.frozenCursors, ChannelUiState boundary picker, the
 * cold-start "Нові пости" boundary rule) hold a frozen reference latched on discrete
 * events. PersistentMap structural sharing keeps put cost at O(log N) — a
 * full O(N) copy would chew through cold-start when TDLib's first
 * UpdateChatReadInbox burst lands hundreds of entries.
 *
 * The **live** form for UI subscribers (PostCard unread strip, ↓N counter,
 * boundary derivedStateOf) is [dev.lyo.hortay.ui.timeline.CursorHolder] —
 * a process-wide [SnapshotStateMap]-backed holder that lets each consumer
 * register a Compose snapshot dependency on just *its* `chatId` key, so an
 * UpdateChatReadInbox for chat Y can't invalidate PostCard X. The previous
 * `staticCompositionLocalOf<ReadCursors>` provider invalidated its whole
 * subtree on every put because static composition locals don't track
 * per-reader subscriptions and PersistentMap puts swap root identity. See
 * `LocalReadCursors.kt` for the holder API; this snapshot type stays for
 * tests, data-layer signatures, and the explicit-freeze use cases.
 */
typealias ReadCursors = PersistentMap<Long, Long>

val EmptyReadCursors: ReadCursors = persistentMapOf()

/**
 * Per-key variant of [isUnreadIn] for callers that already have the cursor
 * for this post's chat in hand (e.g. PostCard reading
 * `CursorHolder[post.chatId]`). The snapshot variant takes the whole map and
 * does its own `cursors[chatId]` lookup — splitting the lookup out lets the
 * Compose snapshot system register the read on just one key.
 *
 * Same album-aware semantics as [isUnreadIn]: returns true while the cursor
 * sits below the highest album-member id, so an external ack landing
 * mid-album doesn't prematurely flip the card to "read".
 */
fun TimelinePost.isUnreadAt(cursor: Long?): Boolean {
    if (parentId != null) return false
    if (cursor == null) return false
    val highestId = albumMessageIds.maxOrNull() ?: id
    return highestId > cursor
}

/**
 * Returns `true` when [TimelinePost.id] is strictly greater than the cursor for
 * its [TimelinePost.chatId]. Posts in chats without a recorded cursor count as
 * read — the cold-start race where `chatCache` hasn't populated yet would
 * otherwise light every visible card up as "unread" for the first ~500 ms after
 * auth, which reads as a UI bug.
 *
 * Discussion-thread replies (`parentId != null`) are always treated as read
 * because the cursor we track is the channel inbox cursor; thread state has a
 * separate `lastReadMessageId` per thread that the comments overlay does not
 * currently surface. Returning false here keeps the UnreadStrip a feed-only
 * affordance — matching the user's stated mental model of "unread post in the
 * feed", not "unread comment in a thread".
 */
fun TimelinePost.isUnreadIn(cursors: ReadCursors): Boolean {
    if (parentId != null) return false
    val cursor = cursors[chatId] ?: return false
    // For albums, the anchor is the LOWEST member id (PostFilterStrategy
    // sorts ascending and picks `first()`). Comparing only anchor.id flips
    // the card to "read" as soon as the cursor passes the first member,
    // even when later members of the same album are still above the cursor
    // — which happens when external acks (e.g. UpdateChatReadInbox from
    // the official Telegram client) land the cursor mid-album. Use the
    // highest member id so the card stays unread until every member has
    // been acked.
    val highestId = albumMessageIds.maxOrNull() ?: id
    return highestId > cursor
}

/**
 * Returns the index of the first unread post in [posts] (iteration order), or
 * `-1` if every post is read (or [posts] is empty).
 */
fun firstUnreadIndex(posts: List<TimelinePost>, cursors: ReadCursors): Int =
    posts.indexOfFirst { it.isUnreadIn(cursors) }

/**
 * Returns [posts] unchanged for both [FeedOrder] values. This is a no-op shim.
 *
 * **Why it's an identity:** the repository layer ([PostsRepository] /
 * [WebFeedSource]) owns the single canonical sort: **descending by date,
 * tie-broken descending by id** (newest = index 0). All callers now rely on
 * that invariant.  [FeedOrder] controls only the `reverseLayout` boolean
 * passed to [androidx.compose.foundation.lazy.LazyColumn] — it does NOT
 * re-sort the list.
 *
 * The function is kept (rather than deleted) so call sites don't all need
 * touching in the same commit.  It accepts `this` by receiver and returns
 * `this` — no copy, no allocation, no recomposition churn from identity
 * change.
 *
 * **Deterministic tie-break lives in the repository.** The previous
 * `OldestUnreadFirst` branch ran `thenBy { it.id }` here to prevent
 * same-date posts from swapping between refreshes.  That guarantee is now
 * upheld by `PostsRepository`'s `sortedByDescending` + secondary key, which
 * is the single authoritative sort location.
 */
fun List<TimelinePost>.orderedFor(@Suppress("UNUSED_PARAMETER") order: FeedOrder): List<TimelinePost> = this

/**
 * Cold-start scroll target. Returns the index of the **oldest unread post**
 * in [posts], or `-1` when nothing qualifies (caught up, empty feed, or
 * cursors not yet loaded).
 *
 * **Descending contract.** [posts] is always newest-first (index 0 = newest),
 * owned by [PostsRepository] / [WebFeedSource]. On that layout, the resume
 * boundary is the LAST entry in the unread block — the oldest unread post
 * sits at the highest index among all unread posts. Both [FeedOrder] values
 * produce the same list order, so the result is **identical regardless of
 * [order]**.  The parameter is retained for API / test compatibility.
 *
 * Fallback. Callers map `-1` to index 0 (newest, top of the descending list)
 * for the "you're caught up" landing.
 *
 * Recency floor ([minUnreadDate]). An aggregated feed across 100+ channels
 * has a different problem from a single-chat unread queue: a dormant channel
 * with a weeks-old unread post sits near the bottom of the descending list
 * and would pull the anchor there. Callers pass the minimum date an unread
 * post must carry to qualify. Older unread posts are still rendered (the user
 * can scroll to them); they just don't influence the cold-start anchor.
 * Default `0L` disables the floor — appropriate for single-channel landings
 * where the user opened a specific chat intentionally.
 */
fun continueReadingIndex(
    @Suppress("UNUSED_PARAMETER") order: FeedOrder,
    posts: List<TimelinePost>,
    cursors: ReadCursors,
    minUnreadDate: Long = 0L,
): Int {
    val qualifies: (TimelinePost) -> Boolean =
        if (minUnreadDate <= 0L) ({ it.isUnreadIn(cursors) })
        else ({ it.isUnreadIn(cursors) && it.date >= minUnreadDate })
    return posts.indexOfLast(qualifies)
}
