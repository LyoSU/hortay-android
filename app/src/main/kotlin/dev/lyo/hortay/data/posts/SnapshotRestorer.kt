package dev.lyo.hortay.data.posts

import android.util.Log
import dev.lyo.hortay.data.PostFilterStrategy
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * Cold-start snapshot helpers and the canonical raw → live-feed fold.
 *
 * Lives next to [PostsRepository] in the same package so the class can keep
 * the cache / mutex / scope ownership while the pure pieces (decision logic
 * on a snapshot of the feed list) stay independently unit-testable.
 *
 *  - [foldRawIntoCurrent] — single merge primitive used by refresh / deep
 *    load / pagination / live ingest / snapshot upgrade. Same partial-album
 *    downgrade guard + album-id-aware dedup the long-form KDoc below
 *    describes.
 */

/**
 * Album-aware fold of a fresh per-message batch into the live feed snapshot.
 *
 * Canonical merge for every code path that ingests a `GetChatHistory` /
 * `SearchChatMessages` result into [PostsRepository]'s `_posts`: full-feed
 * refresh, single-channel deep load, and pagination. They all share two hazards.
 *
 *  1. **Partial-album downgrade.** [raw] is the per-message expansion of an
 *     album: 5 [TimelinePost]s with [TimelinePost.mediaAlbumId] set, each
 *     carrying a 1-item album content. [PostsRepository]'s `coalesceAlbumFragments`
 *     normally plugs members lost to the GetChatHistory window edge, but its
 *     surround fetch can come up short — TDLib FLOOD_WAIT, transient network
 *     blip, or members aged out of the local store. The naive merge ("drop any
 *     current entry that overlaps raw, re-run PostFilterStrategy") then replaces
 *     a known-complete 5-photo merged anchor with a single raw fragment;
 *     mergeAlbumMembers passes a 1-member group through unchanged and the user
 *     sees a 1-photo card. A subsequent `saveSnapshotNow` persists
 *     `albumMessageIds=[]`, the next cold start restores 1 message and never
 *     re-discovers the siblings — stable corruption.
 *
 *  2. **Album duplication on append.** Pagination paths used to drop only
 *     entries whose `(chatId, id)` matched something in `current`, but `current`
 *     only carries the anchor's id. Other album members slipped through, and
 *     PostFilterStrategy would mergeAlbumMembers([merged-anchor (5 items), M2,
 *     M3, M4, M5]) → 5 items flat-mapped from anchor + 1 each from M2..M5 = 9
 *     items with duplicates.
 *
 * Strategy:
 *  - For every (chatId, mediaAlbumId) raw covers, count members against the
 *    known [TimelinePost.albumMessageIds] size on the existing merged anchor.
 *    Strictly fewer raw members than known size → partial → drop the raw
 *    fragment, preserve the anchor.
 *  - Drop existing entries whose anchor.id OR any albumMessageIds member is in
 *    raw's per-message id set, so a raw batch covering the full album cleanly
 *    replaces the anchor instead of stacking on top of it.
 *  - Run [PostFilterStrategy.apply] on the union; it re-merges album members,
 *    drops Unsupported, and resorts.
 */
internal fun foldRawIntoCurrent(
    current: PersistentList<TimelinePost>,
    raw: List<TimelinePost>,
): PersistentList<TimelinePost> {
    val rawByAlbum = raw
        .filter { it.mediaAlbumId != 0L }
        .groupBy { it.chatId to it.mediaAlbumId }
    val knownAlbumSizes = current
        .filter { it.albumMessageIds.size > 1 }
        .associate { (it.chatId to it.mediaAlbumId) to it.albumMessageIds.size }
    val partialAlbumKeys = rawByAlbum.entries
        .mapNotNullTo(HashSet()) { (key, members) ->
            val knownSize = knownAlbumSizes[key]
            if (knownSize != null && members.size < knownSize) key else null
        }
    if (partialAlbumKeys.isNotEmpty()) {
        // Surface this — partial coverage is the symptom of an album that's
        // either aging out of the channel's window or hitting transient
        // FLOOD_WAIT in coalesceAlbumFragments. Either way the existing merged
        // anchor is the more reliable rendering and we skip the raw fragment;
        // the next refresh that catches the album whole will overwrite cleanly.
        // runCatching keeps the helper unit-testable on the JVM where the
        // android.util.Log static stubs throw "not mocked" by default.
        runCatching {
            Log.w("PostsRepository", "preserving ${partialAlbumKeys.size} merged album(s) over partial raw batch")
        }
    }
    val rawSafe = if (partialAlbumKeys.isEmpty()) raw
        else raw.filterNot { (it.chatId to it.mediaAlbumId) in partialAlbumKeys }

    val freshKeys = rawSafe.mapTo(HashSet()) { it.chatId to it.id }
    val keptOld = current.filterNot { post ->
        // Match against EVERY member id, not just the anchor — otherwise a raw
        // batch that contains the album's non-anchor members slips past the
        // de-dup and PostFilterStrategy ends up merging the existing anchor
        // with raw fragments of itself, doubling the items list.
        val keys = post.albumMessageIds.ifEmpty { listOf(post.id) }
        keys.any { id -> (post.chatId to id) in freshKeys }
    }
    // Carry archive-derived state (revisionCount, isDeleted) forward when a raw
    // batch replaces a post we already had in memory. MessageMapper rebuilds posts
    // from TdApi.Message with revisionCount = 0 — without this merge, every PTR /
    // scroll-up / cold-start-backfill resets the EditedChip counter to zero even
    // though [handleContentChanged] correctly bumped it on the original update.
    val carryByKey = HashMap<Pair<Long, Long>, TimelinePost>(current.size)
    current.forEach { post ->
        val keys = post.albumMessageIds.ifEmpty { listOf(post.id) }
        keys.forEach { id -> carryByKey[post.chatId to id] = post }
    }
    val rawMerged = rawSafe.map { fresh ->
        val carry = carryByKey[fresh.chatId to fresh.id]
            ?: fresh.albumMessageIds.firstNotNullOfOrNull { id -> carryByKey[fresh.chatId to id] }
        if (carry == null) fresh
        else fresh.copy(
            revisionCount = maxOf(fresh.revisionCount, carry.revisionCount),
            isDeleted = carry.isDeleted || fresh.isDeleted,
        )
    }
    return PostFilterStrategy.apply(rawMerged + keptOld).toPersistentList()
}
