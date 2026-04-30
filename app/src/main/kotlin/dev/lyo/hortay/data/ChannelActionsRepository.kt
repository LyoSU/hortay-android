package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi

/**
 * Stateless TDLib write-actions for channel/chat operations: react, mute, join, leave.
 *
 * Each method is a thin coroutine wrapper around a single TDLib call wrapped in
 * [warnUnlessCancelled] so the UI can fire-and-forget without crashing on rare TDLib
 * errors (FLOOD_WAIT, restricted reactions, channel left mid-tap…). Repositories that
 * own the resulting state ([PostsRepository] for reactions, this layer for chat info)
 * pick up the change via TDLib's update stream — no need to round-trip the result here.
 */
class ChannelActionsRepository(private val td: TdSender) {

    /**
     * Toggle one of the user's reactions on a message. `isChosen` is the value as the user
     * sees it BEFORE tapping; we flip the side. Passing `isBig=false` and
     * `updateRecentReactions=true` floats the user's most-used reactions to the top of
     * TDLib's reaction picker — same behaviour the official client ships.
     *
     * Custom-emoji reactions go through the same path: TDLib gates them server-side (only
     * Premium users can add custom-emoji reactions to most chats), and on rejection it
     * just no-ops with an error we log and discard. The UI chip still toggles optimistic
     * if the caller wants — TDLib will reconcile via UpdateMessageInteractionInfo.
     */
    suspend fun toggleReaction(
        chatId: Long,
        messageId: Long,
        kind: ReactionKind,
        isChosen: Boolean,
    ) {
        val type = kind.toTd()
        runCatching {
            if (isChosen) {
                td.send(TdApi.RemoveMessageReaction(chatId, messageId, type))
            } else {
                td.send(
                    TdApi.AddMessageReaction(
                        chatId,
                        messageId,
                        type,
                        /* isBig */ false,
                        /* updateRecentReactions */ true,
                    ),
                )
            }
        }.warnUnlessCancelled(TAG, "toggleReaction(${kind.stableKey}, isChosen=$isChosen)")
    }

    private fun ReactionKind.toTd(): TdApi.ReactionType = when (this) {
        is ReactionKind.Emoji -> TdApi.ReactionTypeEmoji(text)
        is ReactionKind.CustomEmoji -> TdApi.ReactionTypeCustomEmoji(customEmojiId)
    }

    /**
     * Mute / unmute a chat. `muteFor` is seconds; 0 = unmuted, very large = "forever"
     * which Telegram represents as the special INT_MAX-ish sentinel. We use 365 days
     * as a pragmatic "forever" — matches the official client's longest preset and avoids
     * the user being surprised by reactivated notifications a year out.
     */
    suspend fun setMuted(chatId: Long, muted: Boolean) {
        val current = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "setMuted/getChat")
            .getOrNull()?.notificationSettings ?: TdApi.ChatNotificationSettings()
        val updated = TdApi.ChatNotificationSettings().apply {
            useDefaultMuteFor = false
            muteFor = if (muted) MUTE_FOREVER_SECONDS else 0
            useDefaultSound = current.useDefaultSound
            soundId = current.soundId
            useDefaultShowPreview = current.useDefaultShowPreview
            showPreview = current.showPreview
            useDefaultMuteStories = current.useDefaultMuteStories
            muteStories = current.muteStories
            useDefaultStorySound = current.useDefaultStorySound
            storySoundId = current.storySoundId
            useDefaultShowStoryPoster = current.useDefaultShowStoryPoster
            showStoryPoster = current.showStoryPoster
            useDefaultDisablePinnedMessageNotifications = current.useDefaultDisablePinnedMessageNotifications
            disablePinnedMessageNotifications = current.disablePinnedMessageNotifications
            useDefaultDisableMentionNotifications = current.useDefaultDisableMentionNotifications
            disableMentionNotifications = current.disableMentionNotifications
        }
        runCatching { td.send(TdApi.SetChatNotificationSettings(chatId, updated)) }
            .warnUnlessCancelled(TAG, "setMuted($muted)")
    }

    suspend fun isMuted(chatId: Long): Boolean {
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "isMuted")
            .getOrNull() ?: return false
        return chat.notificationSettings?.muteFor.let { it != null && it > 0 }
    }

    suspend fun joinChat(chatId: Long) {
        runCatching { td.send(TdApi.JoinChat(chatId)) }
            .warnUnlessCancelled(TAG, "joinChat")
    }

    suspend fun leaveChat(chatId: Long) {
        runCatching { td.send(TdApi.LeaveChat(chatId)) }
            .warnUnlessCancelled(TAG, "leaveChat")
    }

    /**
     * Resolve a channel info bundle for the bottom sheet: title, handle, description,
     * subscriber count, mute state. Several TDLib calls coalesced — kept on this single
     * suspend method so the UI fires one coroutine and lays out when everything is in.
     */
    suspend fun channelInfo(chatId: Long): ChannelInfo? {
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "channelInfo/getChat")
            .getOrNull() ?: return null
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId
        val supergroup = supergroupId?.let {
            runCatching { td.send(TdApi.GetSupergroup(it)) }
                .warnUnlessCancelled(TAG, "channelInfo/getSupergroup")
                .getOrNull()
        }
        val full = supergroupId?.let {
            runCatching { td.send(TdApi.GetSupergroupFullInfo(it)) }
                .warnUnlessCancelled(TAG, "channelInfo/getSupergroupFullInfo")
                .getOrNull()
        }
        val isMember = supergroup?.status?.let { status ->
            status !is TdApi.ChatMemberStatusLeft && status !is TdApi.ChatMemberStatusBanned
        } ?: false
        return ChannelInfo(
            chatId = chatId,
            title = chat.title.orEmpty(),
            handle = supergroup?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" },
            description = full?.description?.takeUnless { it.isNullOrBlank() },
            subscribers = supergroup?.memberCount?.takeIf { it > 0 },
            isMuted = chat.notificationSettings?.muteFor.let { it != null && it > 0 },
            isMember = isMember,
        )
    }

    private companion object {
        const val TAG = "ChannelActionsRepository"
        // 365 days. TDLib treats very-large positive muteFor as "muted indefinitely"; this
        // matches the official client's "Mute forever" preset.
        const val MUTE_FOREVER_SECONDS = 365 * 24 * 60 * 60
    }
}

/** Data bundle backing the channel info bottom sheet. */
data class ChannelInfo(
    val chatId: Long,
    val title: String,
    val handle: String?,
    val description: String?,
    val subscribers: Int?,
    val isMuted: Boolean,
    val isMember: Boolean,
)
