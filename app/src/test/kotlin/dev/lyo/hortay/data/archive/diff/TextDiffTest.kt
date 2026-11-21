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

    /** The diff as readable pieces: `=x` equal, `-x` deleted, `+x` inserted. */
    private fun pieces(old: String, new: String): List<String> {
        assertEquals(old to new, rebuild(old, new))
        return TextDiff.compute(old, new).map {
            when (it.op) {
                TextDiff.Op.Equal -> "=" + old.substring(it.oldStart, it.oldEnd)
                TextDiff.Op.Delete -> "-" + old.substring(it.oldStart, it.oldEnd)
                TextDiff.Op.Insert -> "+" + new.substring(it.newStart, it.newEnd)
            }
        }
    }

    @Test
    fun `a changed view counter reads as one replacement`() {
        assertEquals(listOf("=👁", "-405,7K", "+602,2K"), pieces("👁405,7K", "👁602,2K"))
    }

    @Test
    fun `separators between two digits stay inside the number`() {
        assertEquals(listOf("=x ", "-1.5", "+2.5", "= y"), pieces("x 1.5 y", "x 2.5 y"))
        assertEquals(listOf("-12,345.67", "+12,345.68"), pieces("12,345.67", "12,345.68"))
        assertEquals(listOf("=at ", "-21:49", "+22:05"), pieces("at 21:49", "at 22:05"))
        assertEquals(listOf("-١٢٫٥", "+١٣٫٥"), pieces("١٢٫٥", "١٣٫٥"))
    }

    @Test
    fun `separators not between two digits stay symbols`() {
        assertEquals(listOf("=end", "-.", "+!", "= Next"), pieces("end. Next", "end! Next"))
        assertEquals(listOf("=a", "-,", "+;", "=b"), pieces("a,b", "a;b"))
        assertEquals(listOf("=1, ", "-2", "+3"), pieces("1, 2", "1, 3"))
        assertEquals(listOf("=up ", "-5", "+6", "=."), pieces("up 5.", "up 6."))
        assertEquals(listOf("=see ", "-v1", "+v2", "=."), pieces("see v1.", "see v2."))
        assertEquals(listOf("=time", "-:", "+=", "=12"), pieces("time:12", "time=12"))
    }

    @Test
    fun `identical texts are one equal run`() {
        assertEquals(listOf(TextDiff.Op.Equal), TextDiff.compute("same", "same").map { it.op })
    }
}
