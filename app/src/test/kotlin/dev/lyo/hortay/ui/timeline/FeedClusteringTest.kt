package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.TimelinePost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FeedClusteringTest {

    private val minute = 60_000L

    private fun post(
        chatId: Long,
        id: Long,
        date: Long,
        forwardOf: Pair<Long, Long>? = null,
    ): TimelinePost = TimelinePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = 0L,
        senderName = "ch$chatId",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain("body")),
        views = 0,
        date = date,
        editDate = 0L,
        forwardOrigin = forwardOf?.let { (src, msg) ->
            ForwardOrigin.Channel(
                channelName = "src$src",
                authorSignature = null,
                sourceChatId = src,
                sourceHandle = null,
                sourceMessageId = msg,
            )
        },
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = emptyList(),
    )

    private fun cluster(posts: List<TimelinePost>, expanded: Set<String> = emptySet()) =
        clusterFeed(posts, expanded) { p ->
            ChannelBadge(chatId = p.chatId, title = p.senderName, thumb = null, fileId = null, latestPostDate = p.date)
        }

    @Test
    fun `forwards of one original collapse into the original and list the forwarders`() {
        val original = post(chatId = 1, id = 10, date = 0)
        val fwdA = post(chatId = 2, id = 20, date = 5 * minute, forwardOf = 1L to 10L)
        val fwdB = post(chatId = 3, id = 30, date = 9 * minute, forwardOf = 1L to 10L)
        val other = post(chatId = 4, id = 40, date = 3 * minute)

        val result = cluster(listOf(fwdB, fwdA, other, original))

        assertEquals(listOf(other, original), result.posts)
        assertEquals(listOf(3L, 2L), result.annotations.getValue(original.feedKey()).forwardedBy.map { it.chatId })
    }

    @Test
    fun `forwards without the original keep the earliest copy`() {
        val fwdA = post(chatId = 2, id = 20, date = 1 * minute, forwardOf = 1L to 10L)
        val fwdB = post(chatId = 3, id = 30, date = 8 * minute, forwardOf = 1L to 10L)

        val result = cluster(listOf(fwdB, fwdA))

        assertEquals(listOf(fwdA), result.posts)
        assertEquals(listOf(3L), result.annotations.getValue(fwdA.feedKey()).forwardedBy.map { it.chatId })
    }

    @Test
    fun `a burst of one channel folds under its oldest post`() {
        val burst = (0 until 5).map { i -> post(chatId = 1, id = 100L + i, date = i * 3 * minute) }.reversed()
        val other = post(chatId = 2, id = 7, date = 60 * minute)

        val result = cluster(listOf(other) + burst)

        val oldest = burst.last()
        assertEquals(listOf(other, oldest), result.posts)
        assertEquals(4, result.annotations.getValue(oldest.feedKey()).seriesHidden)
    }

    @Test
    fun `an expanded series shows every post`() {
        val burst = (0 until 4).map { i -> post(chatId = 1, id = 100L + i, date = i * minute) }.reversed()

        val result = cluster(burst, expanded = setOf(burst.last().feedKey()))

        assertEquals(burst, result.posts)
    }

    @Test
    fun `short runs and wide gaps are left alone`() {
        val short = (0 until 3).map { i -> post(chatId = 1, id = 100L + i, date = i * minute) }.reversed()
        assertEquals(short, cluster(short).posts)

        val spread = (0 until 5).map { i -> post(chatId = 1, id = 200L + i, date = i * 20 * minute) }.reversed()
        assertEquals(spread, cluster(spread).posts)
        assertNull(cluster(spread).annotations[spread.last().feedKey()])
    }
}
