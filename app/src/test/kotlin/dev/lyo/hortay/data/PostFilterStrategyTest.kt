package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostFilterStrategyTest {

    @Test
    fun `drops Unsupported content`() {
        val result = PostFilterStrategy.apply(
            listOf(
                post(id = 1L, content = PostContent.Text(FormattedText.plain("hello"))),
                post(id = 2L, content = PostContent.Unsupported("sponsored")),
                post(id = 3L, content = PostContent.Text(FormattedText.plain("world"))),
            ),
        )
        assertEquals(2, result.size)
        assertTrue(result.none { it.content is PostContent.Unsupported })
    }

    @Test
    fun `merges album members into one PhotoAlbum card`() {
        val albumId = 42L
        fun photo(fileId: Int) = TdMedia(fileId = fileId, width = 0, height = 0)
        val members = listOf(
            post(id = 10L, mediaAlbumId = albumId, date = 100L,
                content = PostContent.PhotoAlbum(items = listOf(AlbumItem.Photo(photo(1))), caption = FormattedText.plain("a"))),
            post(id = 11L, mediaAlbumId = albumId, date = 101L,
                content = PostContent.PhotoAlbum(items = listOf(AlbumItem.Photo(photo(2))), caption = FormattedText.Empty)),
            post(id = 12L, mediaAlbumId = albumId, date = 102L,
                content = PostContent.PhotoAlbum(items = listOf(AlbumItem.Photo(photo(3))), caption = FormattedText.Empty)),
        )

        val result = PostFilterStrategy.apply(members)

        assertEquals(1, result.size)
        val merged = result.single().content as PostContent.PhotoAlbum
        assertEquals(3, merged.items.size)
        assertEquals(listOf(10L, 11L, 12L), result.single().albumMessageIds)
    }

    @Test
    fun `album anchor is the lowest-id member regardless of input order`() {
        // Telegram emits all members of a burst-posted album with the same `date`
        // (whole-second resolution). sortedBy { date } would be stable on ties and
        // pick whichever member happened to land first in the upstream — varying
        // between refresh, snapshot restore, live ingest, and pagination paths.
        // That flip caused: (a) LazyColumn re-key churn, (b) the card disappearing
        // from the visible feed back when TimelineViewModel keyed pending on
        // message-id sets (the now-date-based high-water makes this point a no-op
        // since every album member shares one date), and (c) reactions/replyInfo
        // never landing because
        // TDLib only fills those on the FIRST (lowest-id) album member, and our
        // album-aware update lookup needs the anchor to ANCHOR consistently.
        val albumId = 42L
        val sameDate = 1_000L
        fun member(id: Long, fileId: Int) = post(
            id = id, mediaAlbumId = albumId, date = sameDate,
            content = PostContent.PhotoAlbum(
                items = listOf(AlbumItem.Photo(TdMedia(fileId = fileId, width = 1, height = 1))),
                caption = FormattedText.Empty,
            ),
        )
        val ascending = listOf(member(10L, 1), member(11L, 2), member(12L, 3))
        val descending = ascending.reversed()
        val shuffled = listOf(ascending[1], ascending[0], ascending[2])

        for (input in listOf(ascending, descending, shuffled)) {
            val merged = PostFilterStrategy.apply(input).single()
            assertEquals(10L, merged.id, "anchor must be lowest-id regardless of input order")
            assertEquals(listOf(10L, 11L, 12L), merged.albumMessageIds)
            val items = (merged.content as PostContent.PhotoAlbum).items
            assertEquals(3, items.size)
            // Items must read in posting order (lowest id first), so the user sees
            // the album in the same sequence regardless of which path produced it.
            val itemFileIds = items.map { (it as AlbumItem.Photo).media.fileId }
            assertEquals(listOf(1, 2, 3), itemFileIds)
        }
    }

    @Test
    fun `albums of size 1 are passed through unchanged`() {
        val albumId = 7L
        val original = post(
            id = 99L, mediaAlbumId = albumId,
            content = PostContent.PhotoAlbum(
                items = listOf(AlbumItem.Photo(TdMedia(fileId = 1, width = 0, height = 0))),
                caption = FormattedText.Empty,
            ),
        )
        val result = PostFilterStrategy.apply(listOf(original))
        assertEquals(1, result.size)
        assertEquals(99L, result.single().id)
    }

    @Test
    fun `album of documents becomes a FileAlbum of every member with the last member's caption`() {
        // A bot's media group of three files, captioned on the LAST member. The merged card
        // used to keep only the anchor's content — the first file, without the caption.
        val albumId = 9L
        fun doc(id: Long, name: String, caption: String = "") = post(
            id = id, mediaAlbumId = albumId, date = 100L,
            content = document(name, caption),
        )
        val input = listOf(doc(12L, "c.pdf", caption = "Три файли"), doc(10L, "a.pdf"), doc(11L, "b.pdf"))

        val merged = PostFilterStrategy.apply(input).single()

        assertEquals(10L, merged.id, "anchor stays the lowest-id member")
        assertEquals(listOf(10L, 11L, 12L), merged.albumMessageIds)
        val album = merged.content as PostContent.FileAlbum
        assertEquals(listOf(10L, 11L, 12L), album.items.map { it.messageId })
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf"), album.items.map { (it.file as PostContent.Document).fileName })
        assertEquals("Три файли", album.items.last().file.caption.text)
        assertEquals("Три файли", album.captionPlain)
    }

    @Test
    fun `album of audio tracks keeps every track and its caption`() {
        val albumId = 5L
        fun track(id: Long, title: String, caption: String = "") = post(
            id = id, mediaAlbumId = albumId,
            content = PostContent.Audio(
                fileId = id.toInt(), title = title, performer = "", durationSec = 60,
                caption = FormattedText.plain(caption),
            ),
        )
        val merged = PostFilterStrategy.apply(listOf(track(20L, "One"), track(21L, "Two", caption = "Два треки"))).single()

        val album = merged.content as PostContent.FileAlbum
        assertEquals(listOf(20, 21), album.items.map { (it.file as PostContent.Audio).fileId })
        assertEquals("Два треки", album.captionPlain)
    }

    @Test
    fun `re-merging a merged file album with a fresh member does not duplicate items`() {
        val albumId = 3L
        val firstPass = PostFilterStrategy.mergeAlbums(
            listOf(
                post(id = 1L, mediaAlbumId = albumId, content = document("a")),
                post(id = 2L, mediaAlbumId = albumId, content = document("b")),
            ),
        ).single()
        val again = PostFilterStrategy.mergeAlbums(
            listOf(firstPass, post(id = 2L, mediaAlbumId = albumId, content = document("b")), post(id = 3L, mediaAlbumId = albumId, content = document("c"))),
        ).single()

        assertEquals(listOf(1L, 2L, 3L), (again.content as PostContent.FileAlbum).items.map { it.messageId })
    }

    @Test
    fun `a file album with a member of another kind keeps the anchor content`() {
        val albumId = 4L
        val merged = PostFilterStrategy.apply(
            listOf(
                post(id = 1L, mediaAlbumId = albumId, content = document("a")),
                post(id = 2L, mediaAlbumId = albumId, content = PostContent.Text(FormattedText.plain("x"))),
            ),
        ).single()

        assertTrue(merged.content is PostContent.Document)
        assertEquals(listOf(1L, 2L), merged.albumMessageIds)
    }

    @Test
    fun `album keeps the inline keyboard of whichever member carries it`() {
        val albumId = 6L
        val keyboard = InlineKeyboard(
            messageId = 2L,
            rows = kotlinx.collections.immutable.persistentListOf(
                kotlinx.collections.immutable.persistentListOf(InlineButton("Go", InlineButtonAction.Url("https://example.com"))),
            ),
        )
        val merged = PostFilterStrategy.apply(
            listOf(
                post(id = 1L, mediaAlbumId = albumId, content = document("a")),
                post(id = 2L, mediaAlbumId = albumId, content = document("b")).copy(inlineKeyboard = keyboard),
            ),
        ).single()

        assertEquals(keyboard, merged.inlineKeyboard)
    }

    private fun document(name: String, caption: String = "") = PostContent.Document(
        fileId = null, fileName = name, mimeType = "application/pdf",
        sizeBytes = 1L, thumb = null, caption = FormattedText.plain(caption),
    )

    @Test
    fun `standalone posts (mediaAlbumId == 0) are never merged`() {
        val a = post(id = 1L, mediaAlbumId = 0L, content = PostContent.Text(FormattedText.plain("a")))
        val b = post(id = 2L, mediaAlbumId = 0L, content = PostContent.Text(FormattedText.plain("b")))
        val result = PostFilterStrategy.apply(listOf(a, b))
        assertEquals(2, result.size)
    }

    @Test
    fun `sorts newest first`() {
        val older = post(id = 1L, date = 100L, content = PostContent.Text(FormattedText.plain("old")))
        val newer = post(id = 2L, date = 200L, content = PostContent.Text(FormattedText.plain("new")))
        val newest = post(id = 3L, date = 300L, content = PostContent.Text(FormattedText.plain("newest")))
        val result = PostFilterStrategy.apply(listOf(older, newer, newest))
        assertEquals(listOf(3L, 2L, 1L), result.map { it.id })
    }

    private fun post(
        id: Long = 1L,
        chatId: Long = -1001L,
        mediaAlbumId: Long = 0L,
        date: Long = 0L,
        content: PostContent = PostContent.Text(FormattedText.Empty),
    ): TimelinePost = TimelinePost(
        id = id, chatId = chatId, mediaAlbumId = mediaAlbumId,
        senderName = "C", senderHandle = null, avatarThumb = null, avatarFileId = null,
        content = content, views = 0, date = date, editDate = 0L,
        forwardOrigin = null, authorSignature = null, reply = null,
        reactions = Reactions(0, emptyList()), commentCount = null,
        albumMessageIds = emptyList(), parentId = null,
    )
}
