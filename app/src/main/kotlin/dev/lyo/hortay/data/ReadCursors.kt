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
    return id > cursor
}

/**
 * Returns the index of the first unread post in [posts] (iteration order), or
 * `-1` if every post is read (or [posts] is empty).
 */
fun firstUnreadIndex(posts: List<TimelinePost>, cursors: ReadCursors): Int =
    posts.indexOfFirst { it.isUnreadIn(cursors) }

/**
 * "Where should the user resume reading?" — the canonical cold-start scroll
 * target for [dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst].
 *
 * The feed is always rendered in newest-first source order (no per-mode
 * re-sort — see PR rationale in CHANGELOG): the [OldestUnreadFirst] mode is
 * a *scroll-target* setting, not a *sort* setting. The target is the OLDEST
 * unread post (`indexOfLast { isUnread }` in the newest-first list), which is
 * the boundary post — scrolling UP from there walks forward chronologically
 * through newer unread, scrolling DOWN walks back into older read history.
 *
 * Returns `-1` when there is no meaningful resume target:
 *   - `cursors.isEmpty()` — TDLib's `UpdateChatReadInbox` burst hasn't landed
 *     yet (cold-start race). Don't auto-scroll; the LazyColumn renders at its
 *     natural starting position (top = newest) and the cursor pipeline will
 *     repaint when cursors do arrive without yanking the user.
 *   - all caught up — nothing to resume to.
 *   - all unread, no read posts to anchor a "boundary" against — Hortay reads
 *     more like a Twitter feed than a chat inbox; landing on the chronologically
 *     oldest post of the entire feed reads as "the app threw me into ancient
 *     history" rather than "here's where you left off". The user lands at the
 *     top (newest) instead.
 */
fun resumeReadingIndex(posts: List<TimelinePost>, cursors: ReadCursors): Int {
    if (cursors.isEmpty() || posts.isEmpty()) return -1
    var firstUnread = -1
    var lastUnread = -1
    var hasRead = false
    for (i in posts.indices) {
        val post = posts[i]
        if (post.isUnreadIn(cursors)) {
            if (firstUnread < 0) firstUnread = i
            lastUnread = i
        } else if (post.parentId == null) {
            // parentId != null are thread replies; isUnreadIn returns false for
            // them by design, but they also don't count as "real read posts" for
            // the boundary check — they aren't part of the feed-reading flow.
            hasRead = true
        }
    }
    if (lastUnread < 0) return -1  // caught up
    if (!hasRead) return -1  // all unread, no boundary to land on
    return lastUnread  // oldest unread = boundary in newest-first iteration
}
