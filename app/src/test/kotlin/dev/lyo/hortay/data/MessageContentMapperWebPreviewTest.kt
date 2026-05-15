package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coverage guard for [MessageContentMapper.mapWebPreviewKind] and
 * [MessageContentMapper.mapWebPreviewImage].
 *
 * Each `LinkPreviewType*` variant TDLib ships either gets a thumbnail or
 * a kind-driven icon fallback in [dev.lyo.hortay.ui.timeline.PostBody]'s
 * `WebPreviewCard`. The cases below pin both halves of that contract so the
 * UI never regresses to the "empty 72.dp box" rendering that this rework
 * replaces.
 */
class MessageContentMapperWebPreviewTest {

    @Test
    fun `article preview maps photo and Article kind`() {
        val photo = makePhoto(fileId = 11, w = 640, h = 400)
        val type = TdApi.LinkPreviewTypeArticle(photo)
        assertEquals(WebPreviewKind.Article, MessageContentMapper.mapWebPreviewKind(type))
        val image = MessageContentMapper.mapWebPreviewImage(type)
        assertNotNull(image)
        assertEquals(11, image!!.fileId)
    }

    @Test
    fun `video preview prefers cover over video thumbnail`() {
        val cover = makePhoto(fileId = 22, w = 1280, h = 720)
        val video = makeVideo(fileId = 99, thumbFileId = 33)
        val type = TdApi.LinkPreviewTypeVideo(video, cover, 0)
        val image = MessageContentMapper.mapWebPreviewImage(type)
        assertEquals(22, image?.fileId, "cover must win over video.thumbnail")
        assertEquals(WebPreviewKind.Video, MessageContentMapper.mapWebPreviewKind(type))
    }

    @Test
    fun `video preview falls back to video thumbnail when cover is null`() {
        val video = makeVideo(fileId = 99, thumbFileId = 33)
        val type = TdApi.LinkPreviewTypeVideo(video, null, 0)
        val image = MessageContentMapper.mapWebPreviewImage(type)
        assertEquals(33, image?.fileId)
    }

    @Test
    fun `document preview maps thumbnail and Document kind`() {
        val thumb = makeThumbnail(fileId = 44)
        val document = TdApi.Document().apply {
            fileName = "report.pdf"
            mimeType = "application/pdf"
            thumbnail = thumb
            document = makeFile(fileId = 999)
        }
        val type = TdApi.LinkPreviewTypeDocument(document)
        assertEquals(44, MessageContentMapper.mapWebPreviewImage(type)?.fileId)
        assertEquals(WebPreviewKind.Document, MessageContentMapper.mapWebPreviewKind(type))
    }

    @Test
    fun `animation preview maps thumbnail and Animation kind`() {
        val thumb = makeThumbnail(fileId = 55)
        val animation = TdApi.Animation().apply {
            thumbnail = thumb
            animation = makeFile(fileId = 777)
            width = 320
            height = 320
        }
        val type = TdApi.LinkPreviewTypeAnimation(animation)
        assertEquals(55, MessageContentMapper.mapWebPreviewImage(type)?.fileId)
        assertEquals(WebPreviewKind.Animation, MessageContentMapper.mapWebPreviewKind(type))
    }

    @Test
    fun `chat preview yields Chat kind even when photo is null`() {
        val type = TdApi.LinkPreviewTypeChat(null, null, false)
        assertEquals(WebPreviewKind.Chat, MessageContentMapper.mapWebPreviewKind(type))
        assertNull(MessageContentMapper.mapWebPreviewImage(type))
    }

    @Test
    fun `user preview yields User kind`() {
        val type = TdApi.LinkPreviewTypeUser(null, false)
        assertEquals(WebPreviewKind.User, MessageContentMapper.mapWebPreviewKind(type))
    }

    @Test
    fun `unsupported preview maps to Unsupported kind and null image`() {
        val type = TdApi.LinkPreviewTypeUnsupported()
        assertEquals(WebPreviewKind.Unsupported, MessageContentMapper.mapWebPreviewKind(type))
        assertNull(MessageContentMapper.mapWebPreviewImage(type))
    }

    @Test
    fun `gift auction preview maps sticker thumb and Gift kind`() {
        val type = TdApi.LinkPreviewTypeGiftAuction().apply {
            gift = null
        }
        assertEquals(WebPreviewKind.Gift, MessageContentMapper.mapWebPreviewKind(type))
        // No gift attached → no image, but kind still surfaces.
        assertNull(MessageContentMapper.mapWebPreviewImage(type))
    }

    @Test
    fun `embedded video player falls back to thumbnail then to video thumb`() {
        val thumb = makePhoto(fileId = 66, w = 320, h = 180)
        val type = TdApi.LinkPreviewTypeEmbeddedVideoPlayer("https://x", null, thumb, 0, 0, 0)
        assertEquals(66, MessageContentMapper.mapWebPreviewImage(type)?.fileId)
        assertEquals(WebPreviewKind.Video, MessageContentMapper.mapWebPreviewKind(type))
    }

    @Test
    fun `null type maps to Article kind and null image`() {
        assertEquals(WebPreviewKind.Article, MessageContentMapper.mapWebPreviewKind(null))
        assertNull(MessageContentMapper.mapWebPreviewImage(null))
    }

    @Test
    fun `scalar fields are preserved through full mapping`() {
        val photo = makePhoto(fileId = 77, w = 800, h = 800)
        val description = TdApi.FormattedText("hello world", emptyArray())
        val link = TdApi.LinkPreview(
            /* url */ "https://example.com/article",
            /* displayUrl */ "example.com/article",
            /* siteName */ "Example",
            /* title */ "Headline",
            /* description */ description,
            /* author */ "Alice",
            /* type */ TdApi.LinkPreviewTypeArticle(photo),
            /* hasLargeMedia */ true,
            /* showLargeMedia */ true,
            /* showMediaAboveDescription */ true,
            /* skipConfirmation */ false,
            /* showAboveText */ true,
            /* instantViewVersion */ 0,
        )
        val text = TdApi.MessageText(
            TdApi.FormattedText("body", emptyArray()),
            link,
            null,
        )
        val content = MessageContentMapper.map(text, EmptyStringResolver) as PostContent.Text
        val preview = content.webPreview
        assertNotNull(preview)
        assertEquals("https://example.com/article", preview!!.url)
        assertEquals("example.com/article", preview.displayUrl)
        assertEquals("Example", preview.siteName)
        assertEquals("Headline", preview.title)
        assertEquals("hello world", preview.description)
        assertEquals("Alice", preview.author)
        assertEquals(WebPreviewKind.Article, preview.kind)
        assertTrue(preview.showLargeMedia)
        assertTrue(preview.showMediaAboveDescription)
        assertTrue(preview.showAboveText)
        assertEquals(77, preview.image?.fileId)
    }

    @Test
    fun `paid star reaction maps to ReactionKind Paid`() {
        val reactions = TdApi.MessageReactions().apply {
            reactions = arrayOf(
                TdApi.MessageReaction().apply {
                    type = TdApi.ReactionTypePaid()
                    totalCount = 42
                    isChosen = false
                },
            )
        }
        val mapped = MessageContentMapper.mapReactions(reactions)
        assertEquals(1, mapped.items.size)
        assertEquals(42, mapped.totalCount)
        assertEquals(ReactionKind.Paid, mapped.items.first().kind)
    }

    @Test
    fun `paid media with unlocked items keeps starCount`() {
        val photo = makePhoto(fileId = 100, w = 800, h = 600)
        val pm = TdApi.MessagePaidMedia(
            /* starCount */ 50L,
            /* media */ arrayOf(TdApi.PaidMediaPhoto(photo, null)),
            /* caption */ TdApi.FormattedText("body", emptyArray()),
            /* showCaptionAboveMedia */ false,
        )
        val content = MessageContentMapper.map(pm, EmptyStringResolver)
        assertTrue(content is PostContent.PaidMedia, "expected PostContent.PaidMedia, got ${content::class.simpleName}")
        val paid = content as PostContent.PaidMedia
        assertEquals(50L, paid.starCount)
        assertEquals(1, paid.items.size)
        assertEquals(false, paid.isLocked)
    }

    @Test
    fun `paid media with only locked previews surfaces as locked`() {
        val pm = TdApi.MessagePaidMedia(
            /* starCount */ 100L,
            /* media */ arrayOf(TdApi.PaidMediaUnsupported()),
            /* caption */ TdApi.FormattedText("teaser", emptyArray()),
            /* showCaptionAboveMedia */ false,
        )
        val content = MessageContentMapper.map(pm, EmptyStringResolver)
        assertTrue(content is PostContent.PaidMedia)
        val paid = content as PostContent.PaidMedia
        assertEquals(100L, paid.starCount)
        assertEquals(0, paid.items.size)
        assertEquals(true, paid.isLocked)
    }

    @Test
    fun `invoice maps to OpenInSource placeholder`() {
        val invoice = TdApi.MessageInvoice().apply {
            productInfo = TdApi.ProductInfo().apply {
                title = "Premium subscription"
            }
        }
        val content = MessageContentMapper.map(invoice, EmptyStringResolver)
        assertTrue(content is PostContent.OpenInSource)
    }

    @Test
    fun `representative kind allowlist stays exhaustive`() {
        // Pins the union of kinds the UI currently has icons for. If TDLib
        // adds a new LinkPreviewType subclass, the mapper's `else` branch
        // routes it to Unsupported, which still produces a valid render —
        // this test guards the *current* known set.
        val knownKinds = setOf(
            WebPreviewKind.Article,
            WebPreviewKind.Photo,
            WebPreviewKind.Video,
            WebPreviewKind.Animation,
            WebPreviewKind.Audio,
            WebPreviewKind.Document,
            WebPreviewKind.Album,
            WebPreviewKind.App,
            WebPreviewKind.Chat,
            WebPreviewKind.User,
            WebPreviewKind.Sticker,
            WebPreviewKind.StickerSet,
            WebPreviewKind.Story,
            WebPreviewKind.WebApp,
            WebPreviewKind.Gift,
            WebPreviewKind.Invoice,
            WebPreviewKind.Theme,
            WebPreviewKind.External,
            WebPreviewKind.Unsupported,
        )
        // One sample per kind: any TDLib type that maps to this kind.
        val samples: Map<WebPreviewKind, TdApi.LinkPreviewType> = mapOf(
            WebPreviewKind.Article to TdApi.LinkPreviewTypeArticle(null),
            WebPreviewKind.Photo to TdApi.LinkPreviewTypePhoto(makePhoto(1, 1, 1)),
            WebPreviewKind.Video to TdApi.LinkPreviewTypeVideo(makeVideo(1, 1), null, 0),
            WebPreviewKind.Animation to TdApi.LinkPreviewTypeAnimation(TdApi.Animation()),
            WebPreviewKind.Audio to TdApi.LinkPreviewTypeAudio(TdApi.Audio()),
            WebPreviewKind.Document to TdApi.LinkPreviewTypeDocument(TdApi.Document()),
            WebPreviewKind.Album to TdApi.LinkPreviewTypeAlbum(emptyArray(), ""),
            WebPreviewKind.App to TdApi.LinkPreviewTypeApp(null),
            WebPreviewKind.Chat to TdApi.LinkPreviewTypeChat(null, null, false),
            WebPreviewKind.User to TdApi.LinkPreviewTypeUser(null, false),
            WebPreviewKind.Sticker to TdApi.LinkPreviewTypeSticker(TdApi.Sticker()),
            WebPreviewKind.StickerSet to TdApi.LinkPreviewTypeStickerSet(emptyArray()),
            WebPreviewKind.Story to TdApi.LinkPreviewTypeStory(0L, 0),
            WebPreviewKind.WebApp to TdApi.LinkPreviewTypeWebApp(null),
            WebPreviewKind.Gift to TdApi.LinkPreviewTypeGiftAuction(),
            WebPreviewKind.Invoice to TdApi.LinkPreviewTypeInvoice(),
            WebPreviewKind.Theme to TdApi.LinkPreviewTypeTheme(emptyArray(), null),
            WebPreviewKind.External to TdApi.LinkPreviewTypeExternalAudio("u", "audio/mp3", 0),
            WebPreviewKind.Unsupported to TdApi.LinkPreviewTypeUnsupported(),
        )
        assertEquals(knownKinds, samples.keys)
        samples.forEach { (expected, type) ->
            assertEquals(
                expected,
                MessageContentMapper.mapWebPreviewKind(type),
                "${type::class.java.simpleName} must map to $expected",
            )
        }
    }

    // ---------- helpers ----------

    private fun makePhoto(fileId: Int, w: Int, h: Int): TdApi.Photo {
        val size = TdApi.PhotoSize().apply {
            type = "x"
            width = w
            height = h
            photo = makeFile(fileId)
        }
        return TdApi.Photo().apply {
            sizes = arrayOf(size)
        }
    }

    private fun makeVideo(fileId: Int, thumbFileId: Int): TdApi.Video {
        return TdApi.Video().apply {
            width = 1280
            height = 720
            thumbnail = makeThumbnail(thumbFileId)
            video = makeFile(fileId)
        }
    }

    private fun makeThumbnail(fileId: Int): TdApi.Thumbnail = TdApi.Thumbnail().apply {
        width = 320
        height = 320
        file = makeFile(fileId)
    }

    private fun makeFile(fileId: Int): TdApi.File = TdApi.File().apply { id = fileId }

    /** No-op string resolver — none of the cases under test touch the resolver. */
    private object EmptyStringResolver : StringResolver {
        override fun getString(id: Int): String = ""
        override fun getString(id: Int, vararg args: Any?): String = ""
        override fun getQuantityString(id: Int, count: Int, vararg args: Any?): String = ""
    }
}
