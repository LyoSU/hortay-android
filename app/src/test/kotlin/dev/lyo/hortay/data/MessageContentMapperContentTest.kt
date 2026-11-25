package dev.lyo.hortay.data

import dev.lyo.hortay.R
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Mapping of quiz polls, audio album art, invoices, live photos and unknown content. */
class MessageContentMapperContentTest {

    private fun text(s: String) = TdApi.FormattedText(s, emptyArray())

    private fun option(label: String, chosen: Boolean = false) = TdApi.PollOption().apply {
        text = this@MessageContentMapperContentTest.text(label)
        isChosen = chosen
    }

    private fun poll(type: TdApi.PollType, multi: Boolean, vararg options: TdApi.PollOption) =
        TdApi.MessagePoll().apply {
            poll = TdApi.Poll().apply {
                question = text("Q")
                this.options = arrayOf(*options)
                allowsMultipleAnswers = multi
                this.type = type
            }
            description = text("")
        }

    @Test
    fun `multi-answer quiz carries the flag and every correct option`() {
        val msg = poll(
            TdApi.PollTypeQuiz().apply { correctOptionIds = intArrayOf(0, 2) },
            multi = true,
            option("a", chosen = true), option("b"), option("c", chosen = true),
        )
        val kind = (MessageContentMapper.map(msg, FakeStrings) as PostContent.Poll).kind as PollKind.Quiz
        assertTrue(kind.allowsMultipleAnswers)
        assertEquals(setOf(0, 2), kind.correctOptionIds.toSet())
    }

    @Test
    fun `single-answer quiz before answering has no correct options`() {
        val msg = poll(TdApi.PollTypeQuiz(), multi = false, option("a"), option("b"))
        val kind = (MessageContentMapper.map(msg, FakeStrings) as PostContent.Poll).kind as PollKind.Quiz
        assertFalse(kind.allowsMultipleAnswers)
        assertTrue(kind.correctOptionIds.isEmpty())
    }

    @Test
    fun `regular poll keeps its multi-answer flag`() {
        val msg = poll(TdApi.PollTypeRegular(), multi = true, option("a"), option("b"))
        val kind = (MessageContentMapper.map(msg, FakeStrings) as PostContent.Poll).kind as PollKind.Regular
        assertTrue(kind.allowsMultipleAnswers)
    }

    private fun audio(thumb: TdApi.Thumbnail?, mini: TdApi.Minithumbnail?) = TdApi.MessageAudio().apply {
        audio = TdApi.Audio().apply {
            title = "Track"
            performer = "Artist"
            audio = TdApi.File().apply { id = 7 }
            albumCoverThumbnail = thumb
            albumCoverMinithumbnail = mini
        }
        caption = text("")
    }

    private fun jpegThumb(fileId: Int) = TdApi.Thumbnail().apply {
        format = TdApi.ThumbnailFormatJpeg()
        width = 320
        height = 320
        file = TdApi.File().apply { id = fileId }
    }

    private val mini = TdApi.Minithumbnail(40, 40, byteArrayOf(1, 2, 3))

    @Test
    fun `audio album cover maps the thumbnail with the minithumbnail as placeholder`() {
        val content = MessageContentMapper.map(audio(jpegThumb(42), mini), FakeStrings) as PostContent.Audio
        val cover = content.cover!!
        assertEquals(42, cover.fileId)
        assertArrayEquals(byteArrayOf(1, 2, 3), cover.minithumbBytes)
        assertEquals(7, content.fileId)
    }

    @Test
    fun `audio cover falls back to the minithumbnail alone`() {
        val content = MessageContentMapper.map(audio(null, mini), FakeStrings) as PostContent.Audio
        assertNull(content.cover!!.fileId)
    }

    @Test
    fun `audio cover in an undecodable format is skipped`() {
        val tgs = jpegThumb(42).apply { format = TdApi.ThumbnailFormatTgs() }
        val content = MessageContentMapper.map(audio(tgs, null), FakeStrings) as PostContent.Audio
        assertNull(content.cover)
    }

    @Test
    fun `audio without album art has no cover`() {
        val content = MessageContentMapper.map(audio(null, null), FakeStrings) as PostContent.Audio
        assertNull(content.cover)
    }

    @Test
    fun `invoice carries its price and description`() {
        val invoice = TdApi.MessageInvoice().apply {
            productInfo = TdApi.ProductInfo().apply {
                title = "Sticker pack"
                description = this@MessageContentMapperContentTest.text("  Twelve stickers  ")
            }
            currency = "XTR"
            totalAmount = 250
        }
        val content = MessageContentMapper.map(invoice, FakeStrings) as PostContent.OpenInSource
        assertEquals(InvoicePrice("XTR", 250), content.price)
        assertEquals("Sticker pack", content.subtitle)
        assertEquals("Twelve stickers", content.description)
    }

    @Test
    fun `invoice without a currency has no price`() {
        val invoice = TdApi.MessageInvoice().apply { productInfo = TdApi.ProductInfo().apply { title = "X" } }
        val content = MessageContentMapper.map(invoice, FakeStrings) as PostContent.OpenInSource
        assertNull(content.price)
    }

    private fun photo(video: TdApi.Video?) = TdApi.MessagePhoto().apply {
        photo = TdApi.Photo().apply { sizes = emptyArray() }
        this.video = video
        caption = text("")
    }

    @Test
    fun `photo with a motion clip is a live photo`() {
        val album = MessageContentMapper.map(photo(TdApi.Video()), FakeStrings) as PostContent.PhotoAlbum
        assertTrue((album.items.single() as AlbumItem.Photo).isLive)
        val still = MessageContentMapper.map(photo(null), FakeStrings) as PostContent.PhotoAlbum
        assertFalse((still.items.single() as AlbumItem.Photo).isLive)
    }

    @Test
    fun `unknown content becomes a localized placeholder, not a class name`() {
        val content = MessageContentMapper.map(TdApi.MessageUnsupported(), FakeStrings) as PostContent.Unsupported
        assertEquals("str:${R.string.content_unsupported_message}", content.description)
    }

    @Test
    fun `live location expiry is mapping time plus expires_in, zero once stopped`() {
        assertEquals(0L, MessageContentMapper.liveUntilMs(0, 1_000L))
        assertEquals(61_000L, MessageContentMapper.liveUntilMs(60, 1_000L))
        assertTrue(MessageContentMapper.liveUntilMs(Int.MAX_VALUE, 1_000L) > 1_000L)
    }

    @Test
    fun `live location maps as live with its point`() {
        val msg = TdApi.MessageLiveLocation(
            TdApi.LiveLocation(TdApi.Location(50.45, 30.52, 0.0), 900, 0, 0),
            0,
        )
        val content = MessageContentMapper.map(msg, FakeStrings) as PostContent.Location
        assertTrue(content.isLive)
        assertEquals(0L, content.liveUntilMs)
        assertEquals(50.45, content.latitude)
    }
}
