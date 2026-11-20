package dev.lyo.hortay.data.archive.diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TextDiffTest {

    private fun rebuild(old: String, new: String): Pair<String, String> {
        val runs = TextDiff.compute(old, new)
        val o = runs.filter { it.op != TextDiff.Op.Insert }.joinToString("") { old.substring(it.oldStart, it.oldEnd) }
        val n = runs.filter { it.op != TextDiff.Op.Delete }.joinToString("") { new.substring(it.newStart, it.newEnd) }
        return o to n
    }

    @Test
    fun `runs reproduce both texts exactly, whitespace included`() {
        val old = "Hello,  world!\n\nSecond   paragraph ‼️ here."
        val new = "Hello, brave world!\n\nSecond paragraph ‼️ there."
        assertEquals(old to new, rebuild(old, new))
    }

    @Test
    fun `one word fix marks only that word`() {
        val runs = TextDiff.compute("the quick brwn fox", "the quick brown fox")
        val changes = runs.filter { it.op != TextDiff.Op.Equal }
        assertEquals(2, changes.size)
        assertEquals("brwn", "the quick brwn fox".substring(changes[0].oldStart, changes[0].oldEnd))
        assertEquals("brown", "the quick brown fox".substring(changes[1].newStart, changes[1].newEnd))
    }

    @Test
    fun `whitespace between two changes folds into one replacement`() {
        val runs = TextDiff.compute("a b", "c d")
        assertEquals(listOf(TextDiff.Op.Delete, TextDiff.Op.Insert), runs.map { it.op })
    }

    @Test
    fun `emoji with variation selector and surrogate pairs are never split`() {
        val old = "x ‼️ 👨‍👩‍👧 y"
        val new = "x ❗ 👨‍👩‍👧 y"
        val runs = TextDiff.compute(old, new)
        val removed = runs.single { it.op == TextDiff.Op.Delete }
        assertEquals("‼️", old.substring(removed.oldStart, removed.oldEnd))
        assertEquals(old to new, rebuild(old, new))
    }

    @Test
    fun `identical texts are one equal run`() {
        assertEquals(listOf(TextDiff.Op.Equal), TextDiff.compute("same", "same").map { it.op })
    }
}
