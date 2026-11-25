package dev.lyo.hortay.data

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Channel shared-media grid: the message → cell mapping (thumbnail tier, never the inline
 * one), the paging reducers, and the request [ChannelMediaRepository] sends.
 */
class ChannelMediaRepositoryTest {

    private fun file(id: Int) = TdApi.File().apply { this.id = id }

    private fun photoMessage(id: Long, spoiler: Boolean = false) = TdApi.Message().apply {
        this.id = id
        content = TdApi.MessagePhoto().apply {
            photo = TdApi.Photo(
                false,
                TdApi.Minithumbnail(40, 30, byteArrayOf(1, 2, 3)),
                arrayOf(
                    TdApi.PhotoSize("m", file(1), 320, 240, IntArray(0)),
                    TdApi.PhotoSize("y", file(2), 1280, 960, IntArray(0)),
                    TdApi.PhotoSize("w", file(3), 2560, 1920, IntArray(0)),
                ),
            )
            hasSpoiler = spoiler
        }
    }

    private fun videoMessage(id: Long, duration: Int = 42) = TdApi.Message().apply {
        this.id = id
        content = TdApi.MessageVideo().apply {
            video = TdApi.Video().apply {
                this.duration = duration
                width = 1920
                height = 1080
                thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatJpeg(), 320, 180, file(11))
                video = file(12)
            }
        }
    }

    private fun textMessage(id: Long) = TdApi.Message().apply {
        this.id = id
        content = TdApi.MessageText()
    }

    private fun item(id: Long) = photoMessage(id).toChannelMediaItem()!!

    private fun page(ids: List<Long>, next: Long, raw: Int = ids.size) = ChannelMediaPage(
        items = ids.map(::item).toPersistentList(),
        rawCount = raw,
        nextFromMessageId = next,
    )

    @Test
    fun `photo cell uses the thumbnail tier and keeps the full tier for the viewer`() {
        val cell = photoMessage(7L).toChannelMediaItem()!!
        val viewer = cell.viewerItem as AlbumItem.Photo

        assertEquals(7L, cell.messageId)
        assertEquals(1, cell.thumb.fileId, "the grid must not pull the 1280 px inline tier")
        assertEquals(3, viewer.fullscreen.fileId)
        assertNull(cell.durationSec)
        assertFalse(cell.hidden)
    }

    @Test
    fun `video cell uses the video thumbnail and carries its duration`() {
        val cell = videoMessage(8L, duration = 95).toChannelMediaItem()!!
        val viewer = cell.viewerItem as AlbumItem.Video

        assertEquals(11, cell.thumb.fileId)
        assertEquals(12, viewer.playbackFileId)
        assertEquals(95, cell.durationSec)
    }

    @Test
    fun `spoiler media is hidden and non-media messages are dropped`() {
        assertTrue(photoMessage(1L, spoiler = true).toChannelMediaItem()!!.hidden)
        assertNull(textMessage(2L).toChannelMediaItem())
    }

    @Test
    fun `found messages map to a page that counts unsupported messages too`() {
        val found = TdApi.FoundChatMessages(
            -1,
            arrayOf(photoMessage(30L), textMessage(29L), videoMessage(28L)),
            28L,
        )
        val page = found.toChannelMediaPage()

        assertEquals(listOf(30L, 28L), page.items.map { it.messageId })
        assertEquals(3, page.rawCount)
        assertEquals(28L, page.nextFromMessageId)
    }

    @Test
    fun `appending pages dedupes the boundary message and stays idle while a cursor remains`() {
        val first = ChannelMediaState().appended(page(listOf(30L, 29L, 28L), next = 28L), requestedFrom = 0L)
        val second = first.loading().appended(page(listOf(28L, 27L), next = 27L), requestedFrom = 28L)

        assertEquals(listOf(30L, 29L, 28L, 27L), second.items.map { it.messageId })
        assertEquals(27L, second.nextFromMessageId)
        assertEquals(ChannelMediaState.Status.Idle, second.status)
        assertTrue(second.canLoadMore)
    }

    @Test
    fun `history ends on an empty page, a zero cursor or a cursor that did not move`() {
        val base = ChannelMediaState(items = persistentListOf(item(30L)), nextFromMessageId = 30L)

        assertEquals(
            ChannelMediaState.Status.End,
            base.appended(page(emptyList(), next = 12L), requestedFrom = 30L).status,
        )
        assertEquals(
            ChannelMediaState.Status.End,
            base.appended(page(listOf(29L), next = 0L), requestedFrom = 30L).status,
        )
        assertEquals(
            ChannelMediaState.Status.End,
            base.appended(page(listOf(29L), next = 30L), requestedFrom = 30L).status,
        )
    }

    @Test
    fun `a page of only unsupported messages is not the end`() {
        val state = ChannelMediaState().appended(page(emptyList(), next = 40L, raw = 60), requestedFrom = 0L)

        assertTrue(state.items.isEmpty())
        assertEquals(ChannelMediaState.Status.Idle, state.status)
    }

    @Test
    fun `no cells with more history offers to keep looking, not the empty state`() {
        val unshowable = ChannelMediaState().appended(page(emptyList(), next = 40L, raw = 60), requestedFrom = 0L)
        assertEquals(ChannelMediaState.View.KeepLooking, unshowable.view)
        assertEquals(ChannelMediaState.View.Loading, unshowable.loading().view)

        val exhausted = unshowable.appended(page(emptyList(), next = 0L, raw = 0), requestedFrom = 40L)
        assertEquals(ChannelMediaState.View.Empty, exhausted.view)
    }

    @Test
    fun `the screen view follows items first, then the paging status`() {
        assertEquals(ChannelMediaState.View.Loading, ChannelMediaState().view)
        assertEquals(ChannelMediaState.View.Error, ChannelMediaState().failed().view)
        val loaded = ChannelMediaState().appended(page(listOf(30L), next = 0L), requestedFrom = 0L)
        assertEquals(ChannelMediaState.View.Grid, loaded.view)
        assertEquals(ChannelMediaState.View.Grid, loaded.loading().failed().view)
    }

    @Test
    fun `a failure keeps loaded items and the cursor for retry`() {
        val loaded = ChannelMediaState().appended(page(listOf(30L), next = 30L), requestedFrom = 0L)
        val failed = loaded.loading().failed()

        assertEquals(ChannelMediaState.Status.Error, failed.status)
        assertEquals(listOf(30L), failed.items.map { it.messageId })
        assertEquals(30L, failed.nextFromMessageId)
        assertFalse(failed.canLoadMore)
    }

    @Test
    fun `repository asks TDLib for photos and videos from the given cursor`() = runTest {
        val td = FakeTdSender()
        var request: TdApi.SearchChatMessages? = null
        td.onNext { req ->
            request = req as TdApi.SearchChatMessages
            TdApi.FoundChatMessages(-1, arrayOf(photoMessage(20L)), 0L)
        }

        val result = ChannelMediaRepository(td).loadPage(chatId = -100L, fromMessageId = 21L)

        val sent = request!!
        assertEquals(-100L, sent.chatId)
        assertEquals(21L, sent.fromMessageId)
        assertEquals(0, sent.offset)
        assertEquals(CHANNEL_MEDIA_PAGE_SIZE, sent.limit)
        assertTrue(sent.filter is TdApi.SearchMessagesFilterPhotoAndVideo)
        assertEquals(listOf(20L), result.getOrThrow().items.map { it.messageId })
    }

    @Test
    fun `repository surfaces a TDLib error as a failed result`() = runTest {
        val td = FakeTdSender()
        td.onNext { TdApi.Error(420, "FLOOD_WAIT_5") }

        val result = ChannelMediaRepository(td).loadPage(chatId = -100L, fromMessageId = 0L)

        assertTrue(result.isFailure)
    }
}
