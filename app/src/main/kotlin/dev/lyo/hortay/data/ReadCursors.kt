package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * Per-chat read-state cursors. Key = `chatId`, value = `lastReadInboxMessageId`
 * — the highest message id the local user has read in that chat (TDLib mode) or
 * the highest post id they've dwelt on (guest / web mode). A post is **unread**
 * when its `id` is strictly greater than the cursor for its chat.
 *
 * Why a separate observable map (not a field on [TimelinePost]):
 *   - The cursor advances on viewport dwell (~1 s per visible post) and on
 *     external acks (TDLib emits `UpdateChatReadInbox` whenever the user reads
 *     in the official Telegram client). Folding either into [TimelinePost] would
 *     re-emit the entire feed list — `PersistentList` softens the cost but the
 *     dependents (TimelineViewModel.visiblePosts, autodownloader, snapshot
 *     persister) still re-run their filters / coalescers for nothing.
 *   - Storing cursors on the side lets PostCard recompose only when the cursor
 *     for *its* chat changes (via [LocalReadCursors] + Compose snapshot
 *     tracking on the map's identity).
 *
 * Persistent map (not plain Map) so consumers can rely on `@Immutable` semantics
 * — the kotlinx.collections.immutable contract guarantees structural sharing on
 * `put` so per-cursor advances cost O(log N) instead of O(N) copy, and Compose
 * treats the type as stable for skippability.
 */
typealias ReadCursors = PersistentMap<Long, Long>

val EmptyReadCursors: ReadCursors = persistentMapOf()

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
 *
 * The [cursors] parameter is unused by the sort; kept in the signature so
 * the call site doesn't have to branch on feed order to decide whether to
 * pass it.
 */
@Suppress("UNUSED_PARAMETER")
fun List<TimelinePost>.orderedFor(
    order: FeedOrder,
    cursors: ReadCursors,
): List<TimelinePost> = when (order) {
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
 */
fun continueReadingIndex(
    order: FeedOrder,
    posts: List<TimelinePost>,
    cursors: ReadCursors,
): Int = when (order) {
    FeedOrder.Newest -> posts.indexOfLast { it.isUnreadIn(cursors) }
    FeedOrder.OldestUnreadFirst -> posts.indexOfFirst { it.isUnreadIn(cursors) }
}
