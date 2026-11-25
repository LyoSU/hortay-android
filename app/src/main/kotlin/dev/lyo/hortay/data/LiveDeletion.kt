package dev.lyo.hortay.data

/**
 * A live, permanent deletion that takes whole rows out of a list the user may be looking at:
 * the message ids in [messageIds] of chat [chatId] are gone, and each of them was the id of a
 * row that the deletion removes entirely (not kept as a tombstone because the post archive
 * records deletions for that chat — see `ArchiveSettings.keepsDeletionsFor` — and not merely
 * trimmed out of an album).
 *
 * Emitted by the repositories BEFORE they publish the list without those rows, so a screen
 * still showing a row can capture its pixels and let it dissolve (see
 * `ui/media/DissolveEffect.kt`). It is a cosmetic hint, never a source of truth: a screen that
 * misses it simply sees the row disappear with the next list emission.
 */
data class LiveDeletion(val chatId: Long, val messageIds: Set<Long>)

/**
 * The ids of the feed rows of [chatId] that a permanent delete of [deletedIds] removes whole,
 * mirroring the remove branch of `PostsRepository.handleDeleted` (taken when the archive does
 * not keep this chat's deletions):
 *  * a single post whose id was deleted;
 *  * an album all of whose members were deleted, or whose photo / file items all went;
 *  * NOT an album that only shrinks, and NOT an album whose anchor was deleted while other
 *    members survive (its card is re-read from the survivors and comes back).
 *
 * Pure — the caller evaluates it against a snapshot taken outside the `_posts.update` CAS
 * lambda, so the lambda itself stays a pure function of its input.
 */
fun rowsRemovedWhole(posts: List<TimelinePost>, chatId: Long, deletedIds: Set<Long>): Set<Long> {
    if (deletedIds.isEmpty()) return emptySet()
    var out: MutableSet<Long>? = null
    for (post in posts) {
        if (post.chatId != chatId) continue
        if (removesWhole(post, deletedIds)) {
            val set = out ?: HashSet<Long>().also { out = it }
            set += post.id
        }
    }
    return out ?: emptySet()
}

private fun removesWhole(post: TimelinePost, deletedIds: Set<Long>): Boolean {
    val albumIds = post.albumMessageIds
    if (albumIds.isEmpty()) return post.id in deletedIds
    val survived = albumIds.filterNot { it in deletedIds }
    if (survived.size == albumIds.size) return false
    if (survived.isEmpty()) return true
    return when (val content = post.content) {
        is PostContent.PhotoAlbum ->
            albumIds.indices.none { idx -> albumIds[idx] !in deletedIds && idx < content.items.size }
        is PostContent.FileAlbum -> content.items.none { it.messageId !in deletedIds }
        // Degraded album with the anchor alive: only the member list shrinks. Anchor gone
        // with survivors: the card is re-read from them, so the row comes back.
        else -> false
    }
}
