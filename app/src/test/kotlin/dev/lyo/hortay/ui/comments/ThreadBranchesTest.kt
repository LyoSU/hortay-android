package dev.lyo.hortay.ui.comments

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.ThreadRow
import dev.lyo.hortay.data.TimelinePost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Coverage for [visibleThreadItems] and [branchRootOf] — collapsing long reply branches. */
class ThreadBranchesTest {

    private fun post(id: Long, sender: String = "u$id") = TimelinePost(
        id = id,
        chatId = 1L,
        mediaAlbumId = 0L,
        senderName = sender,
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain("c$id")),
        views = 0,
        date = id,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = emptyList(),
    )

    private fun row(id: Long, depth: Int, sender: String = "u$id") =
        ThreadRow(message = post(id, sender), depth = depth, isLastSibling = false)

    /** Top-level comment [rootId] followed by [replies] direct replies with ids rootId+1… */
    private fun branch(rootId: Long, replies: Int): List<ThreadRow> =
        listOf(row(rootId, 0)) + (1..replies).map { row(rootId + it, 1) }

    private fun commentIds(items: List<ThreadItem>): List<Long> =
        items.filterIsInstance<ThreadItem.Comment>().map { it.row.message.id }

    @Test
    fun `empty thread yields no items`() {
        assertTrue(visibleThreadItems(emptyList(), emptySet(), maxVisualDepth = 2).isEmpty())
    }

    @Test
    fun `branch at the threshold stays open`() {
        val rows = branch(100, COLLAPSE_BRANCH_ABOVE)
        val items = visibleThreadItems(rows, emptySet(), maxVisualDepth = 2)
        assertEquals(rows.map { it.message.id }, commentIds(items))
        assertTrue(items.none { it is ThreadItem.MoreReplies })
    }

    @Test
    fun `long branch shows root plus first replies and counts the rest`() {
        val rows = branch(100, 7) + branch(200, 1)
        val items = visibleThreadItems(rows, emptySet(), maxVisualDepth = 2)
        assertEquals(listOf(100L, 101L, 102L, 200L, 201L), commentIds(items))
        val more = items[3] as ThreadItem.MoreReplies
        assertEquals(100L, more.rootId)
        assertEquals(5, more.hiddenCount)
        assertEquals("more-replies:100", more.key)
        assertEquals(1, more.railDepth)
    }

    @Test
    fun `expanded branch shows every reply without a more row`() {
        val rows = branch(100, 7)
        val items = visibleThreadItems(rows, setOf(100L), maxVisualDepth = 2)
        assertEquals(rows.map { it.message.id }, commentIds(items))
        assertTrue(items.none { it is ThreadItem.MoreReplies })
    }

    @Test
    fun `a new reply in a collapsed branch raises the count instead of expanding it`() {
        val before = visibleThreadItems(branch(100, 6), emptySet(), maxVisualDepth = 2)
        val after = visibleThreadItems(branch(100, 7), emptySet(), maxVisualDepth = 2)
        assertEquals(4, (before.last() as ThreadItem.MoreReplies).hiddenCount)
        assertEquals(5, (after.last() as ThreadItem.MoreReplies).hiddenCount)
        assertEquals(commentIds(before), commentIds(after))
    }

    @Test
    fun `nested replies count toward the branch and the shown part is a pre-order prefix`() {
        // 100 → 101 → 102 → 103 (deep chain), then 104, 105 replying to the root.
        val rows = listOf(row(100, 0), row(101, 1), row(102, 2), row(103, 3), row(104, 1), row(105, 1))
        val items = visibleThreadItems(rows, emptySet(), maxVisualDepth = 2)
        assertEquals(listOf(100L, 101L, 102L), commentIds(items))
        assertEquals(3, (items.last() as ThreadItem.MoreReplies).hiddenCount)
    }

    @Test
    fun `rail depth is capped to the visual depth`() {
        val rows = listOf(row(100, 0), row(101, 1), row(102, 2), row(103, 3))
        val items = visibleThreadItems(rows, emptySet(), maxVisualDepth = 2)
        assertEquals(listOf(0, 1, 2, 2), items.map { it.railDepth })
    }

    @Test
    fun `faces are distinct hidden authors capped at the maximum`() {
        val rows = listOf(row(100, 0), row(101, 1), row(102, 1)) +
            listOf("a", "a", "b", "c", "d").mapIndexed { i, s -> row(103L + i, 1, sender = s) }
        val more = visibleThreadItems(rows, emptySet(), maxVisualDepth = 2).last() as ThreadItem.MoreReplies
        assertEquals(listOf("a", "b", "c"), more.faces.map { it.senderName })
        assertEquals(MAX_BRANCH_FACES, more.faces.size)
    }

    @Test
    fun `branch root resolves for a hidden reply, a root and an unknown id`() {
        val rows = branch(100, 7) + branch(200, 2)
        assertEquals(100L, branchRootOf(rows, 106L))
        assertEquals(200L, branchRootOf(rows, 200L))
        assertNull(branchRootOf(rows, 999L))
    }
}
