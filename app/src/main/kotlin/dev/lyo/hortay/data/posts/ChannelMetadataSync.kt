package dev.lyo.hortay.data.posts

import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate

/**
 * Pure-feed mutators for `UpdateChatTitle` / `UpdateChatPhoto`.
 *
 * Extracted from [PostsRepository] so the class body keeps only the
 * mutable-state plumbing (chatCache POJO swap, `_posts.update { … }`),
 * and the per-row decision logic — channel-as-sender vs personal-author
 * (TDLib's `channelContext`) — lives in one place. Same correctness
 * invariants the long-form rationale in CHANGELOG 0.5 describes: only
 * the channel-as-sender row's top-level `avatar*` / `senderName` belong
 * to the host chat; non-anonymous (personal-author) rows carry the
 * admin / foreign-chat identity there and must keep it across renames.
 * The host-channel identity for personal-author rows lives in
 * [TimelinePost.channelContext] and IS this update's target.
 */
internal fun applyChatTitleToFeed(
    current: PersistentList<TimelinePost>,
    chatId: Long,
    newTitle: String,
): PersistentList<TimelinePost> = current.mutate { list ->
    for (i in list.indices) {
        val post = list[i]
        if (post.chatId != chatId) continue
        // Channel-as-sender post: senderName IS the channel name → refresh
        // it. Non-anonymous post (channelContext != null): senderName is the
        // admin / foreign chat — leave it alone, but update the
        // "у Channel" subtitle so the host channel rename surfaces there.
        list[i] = if (post.channelContext == null) {
            post.copy(senderName = newTitle)
        } else {
            post.copy(channelContext = post.channelContext.copy(name = newTitle))
        }
    }
}

internal fun applyChatPhotoToFeed(
    current: PersistentList<TimelinePost>,
    chatId: Long,
    newThumb: ByteArray?,
    newFileId: Int?,
): PersistentList<TimelinePost> = current.mutate { list ->
    for (i in list.indices) {
        val post = list[i]
        if (post.chatId != chatId) continue
        // Same channel-as-sender vs personal-author split as
        // [applyChatTitleToFeed]: only the channel-as-sender row's `avatar*`
        // fields belong to this chat. Non-anonymous posts carry the
        // admin / foreign-chat avatar in `avatar*` — we must not
        // overwrite that with the host channel's new photo. The host
        // channel identity lives in `channelContext` and IS this update's
        // target.
        list[i] = if (post.channelContext == null) {
            post.copy(avatarThumb = newThumb, avatarFileId = newFileId)
        } else {
            post.copy(
                channelContext = post.channelContext.copy(
                    avatarThumb = newThumb,
                    avatarFileId = newFileId,
                ),
            )
        }
    }
}
