package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.ReadCursors
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ── Series identity under history growth ───────────────────────────────────

    /** Five posts of chat 1, three minutes apart, ids 100..104 (newest first). */
    private fun burst(from: Int = 0, count: Int = 5) =
        (from until from + count).map { i -> post(chatId = 1, id = 100L + i, date = i * 3 * minute) }.reversed()

    @Test
    fun `an older post joining a burst moves the kept row and maps the old one to it`() {
        val loaded = burst(from = 1, count = 4)
        val before = cluster(loaded)
        val oldKept = loaded.last()
        assertEquals(listOf(oldKept), before.posts)

        // Older history hydrates: post 100 (3 min before 101) joins the same burst.
        val grown = loaded + post(chatId = 1, id = 100, date = 0)
        val after = cluster(grown)
        val newKept = grown.last()

        assertEquals(listOf(newKept), after.posts)
        assertEquals(newKept.feedKey(), after.keptKeyOf[oldKept.feedKey()])
        assertEquals(newKept.feedKey(), after.resolveKey(oldKept.feedKey()))
        // Every folded member resolves to the visible row.
        grown.dropLast(1).forEach { assertEquals(newKept.feedKey(), after.resolveKey(it.feedKey())) }
    }

    @Test
    fun `a series expanded by member keys stays open when older history joins it`() {
        val loaded = burst(from = 1, count = 4)
        val memberKeys = cluster(loaded).annotations.getValue(loaded.last().feedKey()).seriesMemberKeys
        assertEquals(loaded.map { it.feedKey() }, memberKeys)

        val grown = loaded + post(chatId = 1, id = 100, date = 0)
        val result = cluster(grown, expanded = memberKeys.toSet())

        assertEquals(grown, result.posts)
        assertTrue(result.keptKeyOf.isEmpty())
    }

    @Test
    fun `a series expanded by member keys stays open when a newer post joins it`() {
        val loaded = burst(from = 0, count = 4)
        val memberKeys = cluster(loaded).annotations.getValue(loaded.last().feedKey()).seriesMemberKeys

        val grown = listOf(post(chatId = 1, id = 104, date = 12 * minute)) + loaded
        val result = cluster(grown, expanded = memberKeys.toSet())

        assertEquals(grown, result.posts)
    }

    @Test
    fun `clustering only removes rows and never re-keys a kept one`() {
        val other = post(chatId = 2, id = 7, date = 60 * minute)
        val input = listOf(other) + burst()
        val result = cluster(input)
        val inputKeys = input.map { it.feedKey() }
        result.posts.forEach { assertTrue(it.feedKey() in inputKeys) }
        assertEquals(input.filter { it in result.posts }, result.posts)
    }

    // ── Forward copies ─────────────────────────────────────────────────────────

    @Test
    fun `hidden forward copies map to the kept row and are its ack companions`() {
        val original = post(chatId = 1, id = 10, date = 0)
        val fwdA = post(chatId = 2, id = 20, date = 5 * minute, forwardOf = 1L to 10L)
        val fwdB = post(chatId = 3, id = 30, date = 9 * minute, forwardOf = 1L to 10L)

        val result = cluster(listOf(fwdB, fwdA, original))

        assertEquals(original.feedKey(), result.keptKeyOf[fwdA.feedKey()])
        assertEquals(original.feedKey(), result.keptKeyOf[fwdB.feedKey()])
        assertEquals(listOf(fwdB, fwdA), result.ackCompanions.getValue(original.feedKey()))
        assertEquals(
            listOf(3L, 2L),
            result.annotations.getValue(original.feedKey()).forwardCopies.map { it.chatId },
        )
    }

    @Test
    fun `a forward copy whose kept row folds into a series resolves to the series row`() {
        // Chat 1 bursts four posts; its oldest (100) is also forwarded by chat 2 later on.
        val series = burst(from = 0, count = 4)
        val kept = series.last()
        val copy = post(chatId = 2, id = 50, date = 40 * minute, forwardOf = 1L to 100L)
        // 101 is the forwarded original instead, so the forward kept row is a FOLDED member.
        val copyOfMember = post(chatId = 3, id = 60, date = 41 * minute, forwardOf = 1L to 101L)

        val result = cluster(listOf(copyOfMember, copy) + series)

        assertEquals(listOf(kept), result.posts)
        assertEquals(kept.feedKey(), result.resolveKey(copy.feedKey()))
        assertEquals(kept.feedKey(), result.resolveKey(copyOfMember.feedKey()))
        // Annotations only for visible rows.
        assertEquals(setOf(kept.feedKey()), result.annotations.keys)
    }

    // ── Row-level read state ───────────────────────────────────────────────────

    @Test
    fun `a kept series row stays unread while a folded member is unread`() {
        val series = burst()
        val result = cluster(series)
        val kept = result.posts.single()
        val annotation = result.annotations.getValue(kept.feedKey())
        // Cursor past the kept post (100) but below the newest folded member (104).
        val cursors: ReadCursors = persistentMapOf(1L to 102L)

        assertTrue(rowHasUnread(kept, annotation) { cursors[it] })
        assertEquals(2, rowUnreadCount(kept, annotation) { cursors[it] })
        assertFalse(rowHasUnread(kept, annotation) { if (it == 1L) 104L else null })
    }

    @Test
    fun `a forward row counts once and stays unread while a hidden copy is unread`() {
        val original = post(chatId = 1, id = 10, date = 0)
        val fwd = post(chatId = 2, id = 20, date = 5 * minute, forwardOf = 1L to 10L)
        val result = cluster(listOf(fwd, original))
        val annotation = result.annotations.getValue(original.feedKey())

        val bothUnread: ReadCursors = persistentMapOf(1L to 5L, 2L to 5L)
        assertEquals(1, rowUnreadCount(original, annotation) { bothUnread[it] })

        val copyOnlyUnread: ReadCursors = persistentMapOf(1L to 10L, 2L to 5L)
        assertTrue(rowHasUnread(original, annotation) { copyOnlyUnread[it] })

        val allRead: ReadCursors = persistentMapOf(1L to 10L, 2L to 20L)
        assertFalse(rowHasUnread(original, annotation) { allRead[it] })
    }

    @Test
    fun `the recency floor applies to each folded post by its own date`() {
        val series = burst()
        val result = cluster(series)
        val kept = result.posts.single()
        val annotation = result.annotations.getValue(kept.feedKey())
        val cursors: ReadCursors = persistentMapOf(1L to 50L)

        // Floor between the kept post (0 min) and the newest member (12 min).
        assertEquals(2, rowUnreadCount(kept, annotation, minDate = 9 * minute) { cursors[it] })
        assertFalse(rowHasUnread(kept, annotation, minDate = 13 * minute) { cursors[it] })
    }

    @Test
    fun `the unread boundary lands on a kept row whose only unread posts are folded`() {
        val other = post(chatId = 2, id = 7, date = 60 * minute)
        val series = burst()
        val result = cluster(listOf(other) + series)
        // A read post of chat 3 below them, so there is a read prefix to divide from.
        val read = post(chatId = 3, id = 1, date = -60 * minute)
        val items = (result.posts + read).map(FeedItem::Post)
        // Chat 1 read up to its kept post (only folded members unread); 2 and 3 read.
        val cursors: ReadCursors = persistentMapOf(1L to 100L, 2L to 7L, 3L to 1L)

        val plain = withBoundary(items, cursors, epoch = 1L, recencyCutoffMs = 0L)
        assertTrue(plain.none { it is FeedItem.Boundary })

        val withReadTail = withBoundary(
            items,
            cursors,
            epoch = 1L,
            recencyCutoffMs = 0L,
            annotations = result.annotations,
        )
        assertEquals(2, withReadTail.indexOfFirst { it is FeedItem.Boundary })
    }
}
