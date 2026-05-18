package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.testutil.testPost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupRepliesKeyTest {

    @Test
    fun `thread row keeps reply key when parent backfills later`() {
        val reply = testPost(
            chatId = 1L,
            id = 200L,
            date = 2_000L,
            reply = ReplyPreview(
                authorName = "channel",
                excerpt = "parent",
                isQuote = false,
                replyToChatId = 1L,
                replyToMessageId = 100L,
            ),
        )
        val parent = testPost(chatId = 1L, id = 100L, date = 1_500L)

        val before = groupReplies(listOf(reply))
        val after = groupReplies(listOf(reply, parent))

        assertEquals("post_1_200", before.single().key)
        assertTrue(after.single() is FeedItem.Thread)
        assertEquals(before.single().key, after.single().key)
    }

    @Test
    fun `album key stays stable across anchor flip after loadChannelHistory completes`() {
        // Regression: feed "throws user onto a post" on Back from channel.
        //
        // Cold-start harvests `Chat.lastMessage`, which for an album-tailed channel
        // is typically the HIGHEST member id of that album (last sibling sent).
        // If `coalesceAlbumFragments`' surround fetch fails on cold-start (FLOOD_WAIT
        // / transient network), the feed renders a 1-fragment card with
        // `post.id = lastMessage.id` and `mediaAlbumId = X`.
        //
        // The user opens that channel. [loadChannelHistory] returns the full album.
        // [PostFilterStrategy.mergeAlbumMembers] rebuilds it with `anchor.id` = the
        // LOWEST member id (intentional; first message is the only one that carries
        // `replyInfo` / `reactions` per tdlib/td#2312, and stable across
        // interaction-info updates).
        //
        // A `post.id`-based [FeedItem.key] would flip on that rebuild ("post_1_5" →
        // "post_1_1") and the LazyColumn loses its scroll-anchor key, falls back to
        // the raw `firstVisibleItemIndex`, and the user sees a different post at
        // their viewport top.
        val coldStartFragment = testPost(
            chatId = 1L,
            id = 5L,                 // highest-id member, mirroring lastMessage
            mediaAlbumId = 42L,
            date = 1_000L,
            albumMessageIds = emptyList(),
        )
        val afterFullLoad = testPost(
            chatId = 1L,
            id = 1L,                 // lowest-id member, the canonical anchor
            mediaAlbumId = 42L,
            date = 1_000L,
            albumMessageIds = listOf(1L, 2L, 3L, 4L, 5L),
        )

        val beforeKey = FeedItem.Single(coldStartFragment).key
        val afterKey = FeedItem.Single(afterFullLoad).key

        // The id flipped (5 → 1) but the album identity didn't.
        assertEquals("album_1_42", beforeKey)
        assertEquals(beforeKey, afterKey)
    }

    @Test
    fun `non-album single keeps id-based key`() {
        // Sanity check that standalone (non-album) posts continue to key on
        // `post.id` — they have no mediaAlbumId to fall back to, and the
        // post id IS the stable identity for a single message.
        val post = testPost(chatId = 1L, id = 200L)
        assertEquals("post_1_200", FeedItem.Single(post).key)
    }
}
