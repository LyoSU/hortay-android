package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FeedOrder

/**
 * Reverse feed = newest at the BOTTOM (chat-app idiom). The post data is always
 * sorted descending (newest = index 0); this flag is the ONLY thing that differs
 * between the two feed orders — it flips the LazyColumn's layout direction so
 * index 0 renders at the bottom and scrolling down advances forward in time.
 */
val FeedOrder.reverseLayout: Boolean get() = this == FeedOrder.OldestUnreadFirst

/**
 * Whether the channel/feed should paginate older history. Older posts are always
 * the high-index (oldest) end of the descending data — independent of
 * [reverseLayout]. One condition for both orders replaces the previous
 * direction-aware branch, which (in the old ascending OldestUnreadFirst layout)
 * fired immediately on cold entry because the screen landed at lastIndex and so
 * lastVisible == total - 1 from frame one, causing runaway pagination.
 *
 * @param firstVisible first (closest-to-layout-start) visible item index, or < 0 when none
 * @param lastVisible last visible item index (highest visible index), or < 0 when none
 * @param total total item count
 * @param threshold rows-from-the-older-edge that arm the load
 */
internal fun shouldLoadOlder(firstVisible: Int, lastVisible: Int, total: Int, threshold: Int): Boolean =
    total > 0 && lastVisible >= 0 && lastVisible >= total - threshold
