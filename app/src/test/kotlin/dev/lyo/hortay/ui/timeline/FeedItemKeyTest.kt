package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.testutil.testPost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedItemKeyTest {

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

        val beforeKey = FeedItem.Post(coldStartFragment).key
        val afterKey = FeedItem.Post(afterFullLoad).key

        // The id flipped (5 → 1) but the album identity didn't.
        assertEquals("album_1_42", beforeKey)
        assertEquals(beforeKey, afterKey)
    }

    @Test
    fun `non-album post keeps id-based key`() {
        // Standalone (non-album) posts key on `post.id` — they have no mediaAlbumId
        // to fall back to, and the post id IS the stable identity for a single message.
        val post = testPost(chatId = 1L, id = 200L)
        assertEquals("post_1_200", FeedItem.Post(post).key)
    }

    @Test
    fun `reply post keeps own id key regardless of parent presence in feed`() {
        // Regression: the previous design grouped same-channel parent ↔ reply into
        // a stacked Thread row keyed on `reply.id`. That reshape made the row's key
        // depend on whether a SIBLING post happened to be in the visible list. When
        // a backfill brought the parent (or removed it), the row's identity flipped,
        // LazyColumn lost its `firstVisibleItemKey`, and scroll fell back to the raw
        // integer index — visible as "thrown to the start of the feed" on channel
        // return, and "thrown to the start of the channel" on live reply arrivals.
        //
        // With one post = one row, the reply's key is always `post_<chat>_<reply.id>`,
        // regardless of what else lives in the surrounding list. The inline
        // [ReplyBlock] on the reply's card still conveys the conversational link to
        // the parent.
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
        assertEquals("post_1_200", FeedItem.Post(reply).key)
    }
}
