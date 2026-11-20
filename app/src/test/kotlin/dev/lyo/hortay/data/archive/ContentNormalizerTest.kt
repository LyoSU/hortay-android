package dev.lyo.hortay.data.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ContentNormalizerTest {

    @Test
    fun `poll vote does not change hash`() {
        val before = pollMeta(question = "Q?", voterCounts = listOf(0, 0, 0))
        val after = pollMeta(question = "Q?", voterCounts = listOf(5, 3, 2))
        assertEquals(
            ContentNormalizer.canonicalBytes(before).toList(),
            ContentNormalizer.canonicalBytes(after).toList(),
            "voterCount must not contribute to canonical hash",
        )
    }

    @Test
    fun `edited poll question changes hash`() {
        val v1 = pollMeta(question = "What?", voterCounts = listOf(0, 0))
        val v2 = pollMeta(question = "Why?", voterCounts = listOf(0, 0))
        assertNotEquals(
            ContentNormalizer.canonicalBytes(v1).toList(),
            ContentNormalizer.canonicalBytes(v2).toList(),
        )
    }

    @Test
    fun `edited option text changes hash`() {
        val v1 = pollMeta(question = "Q", voterCounts = listOf(0, 0), optionTexts = listOf("A", "B"))
        val v2 = pollMeta(question = "Q", voterCounts = listOf(0, 0), optionTexts = listOf("A", "C"))
        assertNotEquals(
            ContentNormalizer.canonicalBytes(v1).toList(),
            ContentNormalizer.canonicalBytes(v2).toList(),
        )
    }

    @Test
    fun `text edit changes hash`() {
        val v1 = textMeta("hello")
        val v2 = textMeta("hello world")
        assertNotEquals(
            ContentNormalizer.canonicalBytes(v1).toList(),
            ContentNormalizer.canonicalBytes(v2).toList(),
        )
    }

    @Test
    fun `video duration jitter within 100ms collapses to same hash`() {
        val v1 = videoMeta(durMs = 12_345L)
        val v2 = videoMeta(durMs = 12_399L)
        assertEquals(
            ContentNormalizer.canonicalBytes(v1).toList(),
            ContentNormalizer.canonicalBytes(v2).toList(),
            "duration micro-jitter must collapse — see normalizeMedia durBucket",
        )
    }

    @Test
    fun `video duration over 100ms shift changes hash`() {
        val v1 = videoMeta(durMs = 12_345L)
        val v2 = videoMeta(durMs = 12_500L)
        assertNotEquals(
            ContentNormalizer.canonicalBytes(v1).toList(),
            ContentNormalizer.canonicalBytes(v2).toList(),
        )
    }

    @Test
    fun `entity render payload does not change hash`() {
        val legacy = textMeta(
            "‼ read",
            """[{"offset":0,"length":1,"type":"TextEntityTypeCustomEmoji"},{"offset":2,"length":4,"type":"TextEntityTypeTextUrl"}]""",
        )
        val current = textMeta(
            "‼ read",
            """[{"offset":0,"length":1,"type":"TextEntityTypeCustomEmoji","customEmojiId":5368324170671202286},""" +
                """{"offset":2,"length":4,"type":"TextEntityTypeTextUrl","url":"https://example.com"}]""",
        )
        assertEquals(
            ContentNormalizer.canonicalBytes(legacy).toList(),
            ContentNormalizer.canonicalBytes(current).toList(),
            "rows written before the payload keys existed must keep deduping against new captures",
        )
    }

    @Test
    fun `entity added changes hash`() {
        val plain = textMeta("hello")
        val bold = textMeta("hello", """[{"offset":0,"length":5,"type":"TextEntityTypeBold"}]""")
        assertNotEquals(
            ContentNormalizer.canonicalBytes(plain).toList(),
            ContentNormalizer.canonicalBytes(bold).toList(),
        )
    }

    private fun textMeta(text: String, entitiesJson: String = "[]"): TdlibContentMeta = TdlibContentMeta(
        text = text, entitiesJson = entitiesJson,
        mediaSummaryJson = null, pollJson = null,
        forwardJson = null, replyJson = null,
    )

    private fun videoMeta(durMs: Long): TdlibContentMeta = TdlibContentMeta(
        text = "", entitiesJson = "[]",
        mediaSummaryJson = """{"type":"video","w":640,"h":360,"durationMs":$durMs}""",
        pollJson = null, forwardJson = null, replyJson = null,
    )

    private fun pollMeta(
        question: String,
        voterCounts: List<Int>,
        optionTexts: List<String> = listOf("opt1", "opt2", "opt3").take(voterCounts.size),
    ): TdlibContentMeta {
        val options = optionTexts.zip(voterCounts).joinToString(",") { (t, c) ->
            """{"text":"$t","voterCount":$c}"""
        }
        val pollJson = """{"question":"$question","isAnonymous":true,"options":[$options]}"""
        return TdlibContentMeta(
            text = question, entitiesJson = "[]",
            mediaSummaryJson = null, pollJson = pollJson,
            forwardJson = null, replyJson = null,
        )
    }
}
