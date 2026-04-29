package dev.lyo.telread.data

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
