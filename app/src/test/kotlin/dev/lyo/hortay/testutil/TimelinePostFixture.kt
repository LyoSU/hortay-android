package dev.lyo.hortay.testutil

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.ReplyPreview
import dev.lyo.hortay.data.TimelinePost

/**
 * Shared TimelinePost factory for unit tests. Mirrors the 18-field constructor
 * with sensible defaults. Use named arguments to override only what the test
 * cares about. Pattern adopted from PostFilterStrategyTest.
 */
internal fun testPost(
    id: Long = 1L,
    chatId: Long = -1001L,
    mediaAlbumId: Long = 0L,
    date: Long = 0L,
    content: PostContent = PostContent.Text(FormattedText.Empty),
    senderName: String = "C",
    albumMessageIds: List<Long> = emptyList(),
    reply: ReplyPreview? = null,
    parentId: Long? = null,
): TimelinePost = TimelinePost(
    id = id,
    chatId = chatId,
    mediaAlbumId = mediaAlbumId,
    senderName = senderName,
    senderHandle = null,
    avatarThumb = null,
    avatarFileId = null,
    content = content,
    views = 0,
    date = date,
    editDate = 0L,
    forwardOrigin = null,
    authorSignature = null,
    reply = reply,
    reactions = Reactions(0, emptyList()),
    commentCount = null,
    albumMessageIds = albumMessageIds,
    parentId = parentId,
)
