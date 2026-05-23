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
 * Reorder [posts] for the requested [FeedOrder]. [posts] arrives in the
 * canonical newest-first chronological order that [PostsRepository] /
 * [WebFeedSource] emit; both branches return a fresh list so the caller can
 * hand the result straight to a [androidx.compose.foundation.lazy.LazyColumn]
 * without worrying about stable identity on the source list.
 *
 * `OldestUnreadFirst` is the **reverse-feed idiom**: strict ascending by
 * date — OLDEST on top, NEWEST at the bottom, chronological reading direction
 * (scroll DOWN to advance forward in time). The TimelineScreen cold-start
 * scroll-target picker lands the user at the first unread post (chat-app
 * idiom: "where you left off") for accounts with unread, or at the bottom
 * (= newest) for caught-up accounts.
 *
 * Read/unread state is rendered via [dev.lyo.hortay.ui.timeline.UnreadStrip]
 * per-card and the [dev.lyo.hortay.ui.timeline.UnreadBoundaryRow] divider —
 * it does NOT influence the sort. The previous design ran a stable sort with
 * a read/unread compound key, which could lift a newer read post above an
 * older unread post and read as "broken sort" in a reverse-feed.
 */
fun List<TimelinePost>.orderedFor(order: FeedOrder): List<TimelinePost> = when (order) {
    FeedOrder.Newest -> this
    // Tie-break by id ascending so same-date posts hold a deterministic
    // position across re-sorts. PostFilterStrategy emits same-date posts in
    // a non-deterministic HashMap iteration order, and Kotlin's stable
    // sortedBy preserves that — meaning two posts with `date = 1715607123`
    // could swap places between refreshes and read as "feed jitters" in
    // the reverse-feed layout.
    FeedOrder.OldestUnreadFirst ->
        sortedWith(compareBy<TimelinePost> { it.date }.thenBy { it.id })
}

/**
 * Cold-start scroll target for [order]. Returns `-1` when there's no
 * meaningful target (caught up, empty feed, or cursors not loaded yet).
 *
 *   - `Newest` (newest-first): the oldest unread post is the LAST entry in
 *     the unread block — `indexOfLast { isUnread }`.
 *   - `OldestUnreadFirst` (asc-by-date with read above unread): the first
 *     unread is the boundary between the read block and the unread queue —
 *     `indexOfFirst { isUnread }`. Scrolling there lands the user at "where
 *     they left off", with read history above and unread below.
 *
 * Returns `-1` when nothing is unread — TimelineScreen's caller falls back
 * to `lastIndex` (= newest, at the bottom of the asc-by-date sort) for the
 * canonical "you're caught up, here's the latest" landing.
 *
 * Recency floor ([minUnreadDate]). An aggregated feed across 100+ channels
 * has a different problem from a single-chat unread queue: a dormant
 * channel with a single weeks-old unread post sits at the top of the
 * asc-by-date sort, and `indexOfFirst { isUnread }` strands the user on
 * it — the symptom is "I open the feed, it lands on an old post".
 * Callers pass the minimum date an unread post must carry to qualify as
 * a landing target. Older unread posts are still rendered (the user can
 * scroll up to them), they just don't pull the cold-start anchor. The
 * default `0L` disables the floor — appropriate for single-channel
 * landings where the user opened a specific chat intentionally and the
 * chronology IS the entry point.
 */
fun continueReadingIndex(
    order: FeedOrder,
    posts: List<TimelinePost>,
    cursors: ReadCursors,
    minUnreadDate: Long = 0L,
): Int {
    val qualifies: (TimelinePost) -> Boolean =
        if (minUnreadDate <= 0L) ({ it.isUnreadIn(cursors) })
        else ({ it.isUnreadIn(cursors) && it.date >= minUnreadDate })
    return when (order) {
        FeedOrder.Newest -> posts.indexOfLast(qualifies)
        FeedOrder.OldestUnreadFirst -> posts.indexOfFirst(qualifies)
    }
}
