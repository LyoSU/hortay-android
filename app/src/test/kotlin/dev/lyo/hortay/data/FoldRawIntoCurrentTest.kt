package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.foldRawIntoCurrent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the album-aware merge contract used by full-feed refresh, channel deep
 * load, and pagination. The two failure modes these tests cover are the cause
 * of the "5-photo album becomes 1-photo card after restart" bug:
 *
 *  1. A refresh's GetChatHistory window can clip an album down to fewer
 *     members than we already have merged. The naive merge dropped the merged
 *     anchor and let mergeAlbumMembers re-collapse the partial batch into a
 *     single-member card; a later snapshot save then persisted only one id and
 *     subsequent cold starts couldn't re-discover the lost siblings.
 *
 *  2. Pagination ("loadOlder", "loadChannelHistory") used to dedupe by
 *     (chatId, id) on the existing entry only — but a merged anchor's `id` is
 *     just the oldest member, so the other 4 members of a 5-photo album would
 *     slip past the dedup and PostFilterStrategy would re-merge anchor + 4
 *     fragments into a 9-item double-rendered card.
 */
class FoldRawIntoCurrentTest {

    @Test
    fun `partial raw batch preserves an already-merged album`() {
        val chatId = -1001L
        val albumId = 42L
        val merged = mergedAlbum(chatId, albumId, ids = listOf(10L, 11L, 12L, 13L, 14L))

        // Simulate a refresh that returns only 1 of the 5 album members
        // (older siblings clipped past GetChatHistory(30)'s window edge,
        // coalesceAlbumFragments' surround fetch came up short).
        val partialRaw = listOf(rawAlbumMember(chatId, albumId, id = 14L))

        val result = foldRawIntoCurrent(persistentListOf(merged), partialRaw, maxFeedSize = 1000)

        assertEquals(1, result.size)
        val survivor = result.single()
        assertEquals(merged.id, survivor.id)
        val items = (survivor.content as PostContent.PhotoAlbum).items
        assertEquals(5, items.size, "merged 5-photo album must not downgrade to 1 photo")
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), survivor.albumMessageIds)
    }

    @Test
    fun `complete raw batch replaces the merged anchor without duplicating items`() {
        val chatId = -1001L
        val albumId = 42L
        val merged = mergedAlbum(chatId, albumId, ids = listOf(10L, 11L, 12L, 13L, 14L))

        // Refresh sees the whole album. We must drop the existing merged anchor
        // AND avoid stacking it on top of the raw siblings — otherwise the
        // PostFilterStrategy.mergeAlbumMembers flatMap would return 5 (anchor
        // items) + 5 (raw, each PhotoAlbum-with-1-item) = 10 items.
        val raw = (10L..14L).map { rawAlbumMember(chatId, albumId, id = it) }

        val result = foldRawIntoCurrent(persistentListOf(merged), raw, maxFeedSize = 1000)

        assertEquals(1, result.size)
        val items = (result.single().content as PostContent.PhotoAlbum).items
        assertEquals(5, items.size, "complete raw batch must not duplicate items")
    }

    @Test
    fun `pagination append does not stack album members on top of merged anchor`() {
        // Reproduces the loadOlder / loadChannelHistory bug: current has the
        // merged 5-photo anchor; raw has all 5 members again (TDLib often
        // re-includes the anchor in a paginated response); the seen-set used to
        // only carry the anchor id, letting M2..M5 through and producing a
        // 5+4=9-item double-rendered card.
        val chatId = -1001L
        val albumId = 42L
        val merged = mergedAlbum(chatId, albumId, ids = listOf(10L, 11L, 12L, 13L, 14L))
        val raw = (10L..14L).map { rawAlbumMember(chatId, albumId, id = it) }

        val result = foldRawIntoCurrent(persistentListOf(merged), raw, maxFeedSize = 1000)

        val items = (result.single().content as PostContent.PhotoAlbum).items
        assertEquals(5, items.size, "must not re-stack raw album members onto the merged anchor")
    }

    @Test
    fun `non-album standalone post replaces an older copy in current`() {
        // Sanity: non-album posts go through the same fold, and a fresh raw
        // copy should still take precedence over a stale `current` entry.
        val chatId = -1001L
        val stale = textPost(chatId, id = 99L, views = 10)
        val fresh = textPost(chatId, id = 99L, views = 999)

        val result = foldRawIntoCurrent(persistentListOf(stale), listOf(fresh), maxFeedSize = 1000)

        assertEquals(1, result.size)
        assertEquals(999, result.single().views)
    }

    @Test
    fun `new album not previously merged passes through normally`() {
        // No `current` entry → no knownAlbumSize → not flagged as partial.
        // Standard PostFilterStrategy merge takes over.
        val chatId = -1001L
        val albumId = 7L
        val raw = (1L..3L).map { rawAlbumMember(chatId, albumId, id = it) }

        val result = foldRawIntoCurrent(persistentListOf(), raw, maxFeedSize = 1000)

        assertEquals(1, result.size)
        val merged = result.single()
        val items = (merged.content as PostContent.PhotoAlbum).items
        assertEquals(3, items.size)
        assertEquals(listOf(1L, 2L, 3L), merged.albumMessageIds)
    }

    @Test
    fun `unrelated current entries are preserved across the fold`() {
        val chatId = -1001L
        val unrelated = textPost(chatId, id = 50L, views = 5)
        val albumId = 42L
        val merged = mergedAlbum(chatId, albumId, ids = listOf(10L, 11L, 12L))
        val current = persistentListOf(unrelated, merged)
        val partialRaw = listOf(rawAlbumMember(chatId, albumId, id = 12L))

        val result = foldRawIntoCurrent(current, partialRaw, maxFeedSize = 1000)

        assertTrue(result.any { it.id == 50L }, "unrelated post must survive the fold")
        val mergedSurvivor = result.firstOrNull { it.id == merged.id }
        assertNotNull(mergedSurvivor)
        val items = (mergedSurvivor!!.content as PostContent.PhotoAlbum).items
        assertEquals(3, items.size)
    }

    @Test
    fun `feed cap is honoured`() {
        val chatId = -1001L
        val raw = (1L..50L).map { textPost(chatId, id = it, views = 0) }
        val result = foldRawIntoCurrent(persistentListOf(), raw, maxFeedSize = 10)
        assertEquals(10, result.size)
    }

    private fun mergedAlbum(chatId: Long, albumId: Long, ids: List<Long>): TimelinePost {
        val items = ids.map { id -> AlbumItem.Photo(TdMedia(fileId = id.toInt(), width = 1, height = 1)) }
        return basePost(
            id = ids.first(),
            chatId = chatId,
            mediaAlbumId = albumId,
            content = PostContent.PhotoAlbum(items = items, caption = FormattedText.Empty),
            albumMessageIds = ids,
        )
    }

    private fun rawAlbumMember(chatId: Long, albumId: Long, id: Long): TimelinePost = basePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = albumId,
        content = PostContent.PhotoAlbum(
            items = listOf(AlbumItem.Photo(TdMedia(fileId = id.toInt(), width = 1, height = 1))),
            caption = FormattedText.Empty,
        ),
        albumMessageIds = emptyList(),
    )

    private fun textPost(chatId: Long, id: Long, views: Int): TimelinePost = basePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = 0L,
        content = PostContent.Text(FormattedText.plain("post-$id")),
        albumMessageIds = emptyList(),
        views = views,
    )

    private fun basePost(
        id: Long,
        chatId: Long,
        mediaAlbumId: Long,
        content: PostContent,
        albumMessageIds: List<Long>,
        views: Int = 0,
    ): TimelinePost = TimelinePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = mediaAlbumId,
        senderName = "Channel",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = content,
        views = views,
        // Seed dates so PostFilterStrategy's stable sort doesn't reorder
        // album members across runs — the lowest id wins as the anchor.
        date = id,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = albumMessageIds,
        parentId = null,
    )
}
