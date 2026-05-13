package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReactionTogglePolicyTest {

    private val thumbsUp = ReactionKind.Emoji("👍")
    private val heart = ReactionKind.Emoji("❤️")

    @Test
    fun `adding a reaction to an empty bucket appends a new item`() {
        val result = ReactionTogglePolicy.apply(
            current = Reactions(totalCount = 0, items = emptyList()),
            kind = thumbsUp,
            nowChosen = true,
        )
        assertEquals(1, result.totalCount)
        assertEquals(1, result.items.size)
        assertEquals(thumbsUp, result.items[0].kind)
        assertEquals(1, result.items[0].count)
        assertTrue(result.items[0].isChosen)
    }

    @Test
    fun `adding a reaction that already exists increments the count and flips isChosen on`() {
        val result = ReactionTogglePolicy.apply(
            current = Reactions(totalCount = 5, items = listOf(ReactionItem(thumbsUp, 5, isChosen = false))),
            kind = thumbsUp,
            nowChosen = true,
        )
        assertEquals(6, result.totalCount)
        assertEquals(6, result.items[0].count)
        assertTrue(result.items[0].isChosen)
    }

    @Test
    fun `adding a fresh reaction preserves other buckets`() {
        val result = ReactionTogglePolicy.apply(
            current = Reactions(totalCount = 3, items = listOf(ReactionItem(heart, 3, isChosen = false))),
            kind = thumbsUp,
            nowChosen = true,
        )
        assertEquals(4, result.totalCount)
        assertEquals(2, result.items.size)
        assertEquals(heart, result.items[0].kind)
        assertEquals(3, result.items[0].count)
        assertEquals(thumbsUp, result.items[1].kind)
        assertEquals(1, result.items[1].count)
        assertTrue(result.items[1].isChosen)
    }

    @Test
    fun `removing your reaction from a bucket of one drops the bucket`() {
        val result = ReactionTogglePolicy.apply(
            current = Reactions(totalCount = 1, items = listOf(ReactionItem(thumbsUp, 1, isChosen = true))),
            kind = thumbsUp,
            nowChosen = false,
        )
        assertEquals(0, result.totalCount)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `removing your reaction from a shared bucket decrements and flips isChosen off`() {
        val result = ReactionTogglePolicy.apply(
            current = Reactions(totalCount = 7, items = listOf(ReactionItem(thumbsUp, 7, isChosen = true))),
            kind = thumbsUp,
            nowChosen = false,
        )
        assertEquals(6, result.totalCount)
        assertEquals(6, result.items[0].count)
        assertFalse(result.items[0].isChosen)
    }

    @Test
    fun `removing a reaction you never had is a no-op`() {
        val current = Reactions(totalCount = 2, items = listOf(ReactionItem(heart, 2, isChosen = false)))
        val result = ReactionTogglePolicy.apply(current, thumbsUp, nowChosen = false)
        assertEquals(current, result)
    }

    @Test
    fun `custom emoji buckets are keyed by id`() {
        val sticker = ReactionKind.CustomEmoji(customEmojiId = 42L)
        val result = ReactionTogglePolicy.apply(
            current = Reactions(totalCount = 0, items = emptyList()),
            kind = sticker,
            nowChosen = true,
        )
        assertEquals(1, result.items.size)
        assertEquals(sticker, result.items[0].kind)
    }

    @Test
    fun `toggle is reversible — apply then revert returns the original`() {
        val original = Reactions(
            totalCount = 5,
            items = listOf(
                ReactionItem(thumbsUp, 3, isChosen = false),
                ReactionItem(heart, 2, isChosen = false),
            ),
        )
        val added = ReactionTogglePolicy.apply(original, thumbsUp, nowChosen = true)
        val reverted = ReactionTogglePolicy.apply(added, thumbsUp, nowChosen = false)
        assertEquals(original, reverted)
    }
}
