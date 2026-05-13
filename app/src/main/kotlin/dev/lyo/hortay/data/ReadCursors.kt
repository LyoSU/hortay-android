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
 * Pure test target. Returns `true` when [TimelinePost.id] is strictly greater
 * than the cursor for its [TimelinePost.chatId]. Posts in chats without a
 * recorded cursor count as read — the cold-start race where `chatCache` hasn't
 * populated yet would otherwise light every visible card up as "unread" for the
 * first ~500 ms after auth, which reads as a UI bug.
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
    return id > cursor
}

/**
 * Returns the index of the first unread post in [posts], or `-1` if every post
 * is read (or [posts] is empty). Used by the "Continue reading" affordance to
 * scroll the feed to the boundary between read and unread.
 *
 * "First" is interpreted in iteration order of the supplied list — callers in
 * `Newest`-orientation pass the list as-displayed (newest-at-top) and the first
 * unread is the topmost still-unread row; `OldestUnreadFirst` callers pass the
 * already-sorted list and get index 0 when anything is unread.
 */
fun firstUnreadIndex(posts: List<TimelinePost>, cursors: ReadCursors): Int =
    posts.indexOfFirst { it.isUnreadIn(cursors) }

/**
 * Reorder [posts] for the requested [FeedOrder]. [posts] arrives in the
 * canonical newest-first chronological order that [PostsRepository] / [WebFeedSource]
 * emit; both branches return a fresh list so the caller can hand the result
 * straight to a [androidx.compose.foundation.lazy.LazyColumn] without worrying
 * about stable identity on the source list.
 *
 * `OldestUnreadFirst` mirrors the **chat-app idiom** that Telegram-Android,
 * WhatsApp, and Slack all settle on for an inbox:
 *   - Read posts on top (asc by date), unread posts below (asc by date).
 *   - `compareBy({ isUnreadIn(cursors) }, { date })` — Kotlin's stable sort
 *     puts `false < true`, so read posts (where `isUnread = false`) lead.
 *     Within each block, ascending date keeps "newspaper-column" reading
 *     direction.
 *   - TimelineScreen auto-scrolls to the boundary on mount so the user
 *     lands at the FIRST unread post — scrolling DOWN walks forward through
 *     unread; scrolling UP walks backward into already-read history. Same
 *     gesture model as opening a chat in any modern messenger.
 *   - Read posts render dimmed (alpha 0.55) so the visual hierarchy reads
 *     "primary = unread, supporting = history" without changing the
 *     chronological layout.
 *
 * Callers in TimelineScreen pin [cursors] to a frozen snapshot captured at
 * refresh boundaries (initial mount, pull-to-refresh) so mid-scroll dwell
 * acks don't shuffle posts across the unread/read boundary under the
 * user's eyes. The TDLib cursor itself continues advancing in the
 * background — only the SORT's view of cursors is frozen. On pull-to-
 * refresh the snapshot updates and acked posts migrate from unread to read
 * block as a single visible reordering.
 */
fun List<TimelinePost>.orderedFor(
    order: FeedOrder,
    cursors: ReadCursors,
): List<TimelinePost> = when (order) {
    FeedOrder.Newest -> this
    FeedOrder.OldestUnreadFirst -> sortedWith(
        compareBy({ it.isUnreadIn(cursors) }, { it.date }),
    )
}

/**
 * "Where should the user continue reading from?" — the canonical target for
 * the [dev.lyo.hortay.ui.timeline.ContinueReadingChip] in the given [order].
 *
 * The semantic is "oldest unread post" (= where reading should resume,
 * chronologically). The index of that post depends on the iteration order:
 *   - In `Newest` (newest-first), oldest unread is the LAST entry in the
 *     unread block — `indexOfLast { isUnread }`. Scrolling to it puts the
 *     user just above the unread/read boundary.
 *   - In `OldestUnreadFirst` (unread-only, ascending date), oldest unread is
 *     simply index 0.
 *
 * Returns `-1` when there's nothing to continue to (all read, empty feed).
 */
fun continueReadingIndex(
    order: FeedOrder,
    posts: List<TimelinePost>,
    cursors: ReadCursors,
): Int = when (order) {
    FeedOrder.Newest -> posts.indexOfLast { it.isUnreadIn(cursors) }
    FeedOrder.OldestUnreadFirst -> posts.indexOfFirst { it.isUnreadIn(cursors) }
}
