package dev.lyo.hortay.data.archive.diff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostDiffTest {
    @Test
    fun lineLevelDiff_picked_whenMultilinePost() {
        val r = PostDiff.compute("a\nb\nc\nd", "a\nB\nc\nd")
        assertEquals(PostDiff.Granularity.LINE, r.granularity)
        assertTrue(r.segments.any { it is PostDiffSegment.Deleted })
        assertTrue(r.segments.any { it is PostDiffSegment.Inserted })
    }

    @Test
    fun sentenceLevelDiff_picked_whenSingleParagraphMultiSentence() {
        val r = PostDiff.compute("Hello. World. End.", "Hello. World now. End.")
        assertEquals(PostDiff.Granularity.SENTENCE, r.granularity)
    }

    @Test
    fun wordLevelDiff_picked_whenShortSingleSentence() {
        val r = PostDiff.compute("Sale up to 3000", "Sale up to 5000")
        assertEquals(PostDiff.Granularity.WORD, r.granularity)
    }

    @Test
    fun identicalTexts_produceOnlyUnchanged() {
        val r = PostDiff.compute("hello world", "hello world")
        assertTrue(r.segments.all { it is PostDiffSegment.Unchanged })
    }
}
