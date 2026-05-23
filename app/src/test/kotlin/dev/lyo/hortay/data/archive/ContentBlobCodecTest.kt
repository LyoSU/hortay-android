package dev.lyo.hortay.data.archive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContentBlobCodecTest {

    private val sampleMeta = TdlibContentMeta(
        text = "hello world",
        entitiesJson = "[]",
        mediaSummaryJson = null,
        pollJson = null,
        forwardJson = null,
        replyJson = null,
    )

    @Test
    fun `encode then decode roundtrip preserves all fields`() {
        val blob = ContentBlobCodec.encode(sampleMeta)
        val decoded = ContentBlobCodec.decode(blob)

        assertEquals(sampleMeta.text, decoded.text)
        assertEquals(sampleMeta.entitiesJson, decoded.entitiesJson)
        assertEquals(sampleMeta.mediaSummaryJson, decoded.mediaSummaryJson)
        assertEquals(sampleMeta.pollJson, decoded.pollJson)
        assertEquals(sampleMeta.forwardJson, decoded.forwardJson)
        assertEquals(sampleMeta.replyJson, decoded.replyJson)
    }

    @Test
    fun `encoding is deterministic — same input produces identical bytes`() {
        val blob1 = ContentBlobCodec.encode(sampleMeta)
        val blob2 = ContentBlobCodec.encode(sampleMeta)

        assertArrayEquals(blob1, blob2)
        assertEquals(ContentBlobCodec.hash(blob1), ContentBlobCodec.hash(blob2))
    }

    @Test
    fun `hash differs when content differs`() {
        val metaA = sampleMeta
        val metaB = sampleMeta.copy(text = "different text")

        val hashA = ContentBlobCodec.hash(ContentBlobCodec.encode(metaA))
        val hashB = ContentBlobCodec.hash(ContentBlobCodec.encode(metaB))

        assert(hashA != hashB) { "Hashes must differ for different content" }
    }

    @Test
    fun `decode throws on wrong magic`() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 0x01)
        assertThrows<IllegalArgumentException> {
            ContentBlobCodec.decode(garbage)
        }
    }

    @Test
    fun `roundtrip preserves optional fields when populated`() {
        val meta = TdlibContentMeta(
            text = "caption",
            entitiesJson = """[{"offset":0,"length":7,"type":"bold"}]""",
            mediaSummaryJson = """{"type":"photo","count":1,"w":1280,"h":720}""",
            pollJson = null,
            forwardJson = """{"chatId":123,"messageId":456}""",
            replyJson = """{"messageId":789}""",
        )
        val decoded = ContentBlobCodec.decode(ContentBlobCodec.encode(meta))

        assertEquals(meta, decoded)
    }
}
