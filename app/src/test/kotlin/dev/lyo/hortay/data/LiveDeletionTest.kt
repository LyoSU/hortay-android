package dev.lyo.hortay.data

import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LiveDeletionTest {

    private val chat = 10L

    private fun post(
        id: Long,
        chatId: Long = chat,
        albumIds: List<Long> = emptyList(),
        content: PostContent = PostContent.Text(FormattedText.plain("body")),
    ) = TimelinePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = if (albumIds.isEmpty()) 0L else 99L,
        senderName = "ch",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = content,
        views = 0,
        date = 0L,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = albumIds,
    )

    private fun photoAlbum(n: Int) = PostContent.PhotoAlbum(
        items = (1..n).map { AlbumItem.Photo(TdMedia(fileId = it, width = 1, height = 1)) },
        caption = FormattedText.plain(""),
    )

    private fun fileAlbum(ids: List<Long>) = PostContent.FileAlbum(
        items = persistentListOf<FileAlbumItem>().addAll(
            ids.map { id ->
                FileAlbumItem(
                    messageId = id,
                    file = PostContent.Document(
                        fileId = id.toInt(),
                        fileName = "f$id",
                        mimeType = "application/pdf",
                        sizeBytes = 1L,
                        thumb = null,
                        caption = FormattedText.plain(""),
                    ),
                )
            },
        ),
    )

    @Test
    fun `a deleted single post is removed whole`() {
        val posts = listOf(post(1), post(2), post(3))
        assertEquals(setOf(2L), rowsRemovedWhole(posts, chat, setOf(2L)))
    }

    @Test
    fun `other chats and untouched posts are ignored`() {
        val posts = listOf(post(1, chatId = 11L), post(2))
        assertEquals(emptySet<Long>(), rowsRemovedWhole(posts, chat, setOf(1L)))
        assertEquals(emptySet<Long>(), rowsRemovedWhole(posts, chat, emptySet()))
    }

    @Test
    fun `a fully deleted album is removed whole`() {
        val posts = listOf(post(5, albumIds = listOf(5L, 6L, 7L), content = photoAlbum(3)))
        assertEquals(setOf(5L), rowsRemovedWhole(posts, chat, setOf(5L, 6L, 7L)))
    }

    @Test
    fun `an album that only shrinks does not dissolve`() {
        val posts = listOf(post(5, albumIds = listOf(5L, 6L, 7L), content = photoAlbum(3)))
        assertEquals(emptySet<Long>(), rowsRemovedWhole(posts, chat, setOf(6L)))
        // The anchor goes, photos remain: the card is trimmed onto a survivor.
        assertEquals(emptySet<Long>(), rowsRemovedWhole(posts, chat, setOf(5L)))
    }

    @Test
    fun `a photo album whose remaining members have no items left is removed whole`() {
        // Degraded: three members known, only one item loaded — deleting its member empties it.
        val posts = listOf(post(5, albumIds = listOf(5L, 6L, 7L), content = photoAlbum(1)))
        assertEquals(setOf(5L), rowsRemovedWhole(posts, chat, setOf(5L)))
    }

    @Test
    fun `file albums keep their row while any file survives`() {
        val ids = listOf(5L, 6L)
        val posts = listOf(post(5, albumIds = ids, content = fileAlbum(ids)))
        assertEquals(emptySet<Long>(), rowsRemovedWhole(posts, chat, setOf(5L)))
        val loaded = listOf(post(5, albumIds = listOf(5L, 6L, 7L), content = fileAlbum(ids)))
        assertEquals(setOf(5L), rowsRemovedWhole(loaded, chat, setOf(5L, 6L)))
    }

    @Test
    fun `a degraded album losing its anchor comes back from survivors and does not dissolve`() {
        val posts = listOf(post(5, albumIds = listOf(5L, 6L)))
        assertEquals(emptySet<Long>(), rowsRemovedWhole(posts, chat, setOf(5L)))
        assertEquals(emptySet<Long>(), rowsRemovedWhole(posts, chat, setOf(6L)))
        assertEquals(setOf(5L), rowsRemovedWhole(posts, chat, setOf(5L, 6L)))
    }
}
